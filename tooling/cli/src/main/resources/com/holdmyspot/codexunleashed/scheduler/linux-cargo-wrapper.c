/*
 * Runs one Cargo compiler invocation under the root build scheduler's control.
 *
 * Invocation (as RUSTC_WRAPPER, by Cargo, as the build user):
 *   linux-cargo-wrapper RUSTC ARG...
 *
 * Each attempt is a forked child of this wrapper that blocks until the root
 * controller has moved it into the attempt's cgroup and instrumented it, then
 * replaces itself with RUSTC. Cargo's standard input and output reach the
 * compiler directly; its standard error passes through this wrapper, which
 * drops rustc artifact notifications that a retried attempt repeats, because
 * Cargo panics on a duplicate one. The wrapper exits with the status that the
 * controller selects, so Cargo sees one compiler run however many attempts the
 * controller needed.
 *
 * A build defines CODEX_TEST_ALLOW_NONROOT_CONTROLLER only for the native test:
 * it accepts the wrapper's own effective uid in place of uid 0 as the owner of
 * the configuration and as the controller's peer, and CODEX_TEST_TRUSTED_DIRECTORY
 * then names the directory where the configuration ownership check begins,
 * because the test cannot control the ownership of that directory's ancestors.
 * No environment variable or configuration entry relaxes either check.
 *
 * Linux 5.9 or later is required (pidfd_open and close_range).
 *
 * # Linux Cargo wrapper protocol, version 2
 *
 * ## Configuration file
 * The wrapper finds its configuration through `CODEX_LINUX_CARGO_WRAPPER_CONFIG` (absolute path).
 * The file and every ancestor directory must be owned by uid 0 and not group- or other-writable; the
 * wrapper opens it with O_NOFOLLOW and checks the opened descriptor with fstat. Content is ASCII lines
 * `key=value\n`, no comments, no blank lines, each key exactly once, in this order:
 *
 *     version=2
 *     controller=<absolute path of the controller's Unix stream socket>
 *     timeout_millis=<positive decimal; limit on every blocking read of the controller>
 *
 * Any other content is a configuration error (exit 125 with a diagnostic on stderr).
 *
 * ## Framing
 * Each message is a 4-byte big-endian unsigned length followed by that many payload bytes; the
 * length is at most 1048576. The payload is a sequence of fields, each terminated by one NUL byte.
 * The first field is the message type. Decimal numbers are ASCII without sign or leading zeros
 * (zero is `0`). A message with a wrong field count, unknown type, or malformed number is a protocol
 * error: the wrapper kills and reaps any child and exits 125; the controller closes the connection.
 *
 * ## Wrapper -> controller
 * - `register` version=`2`, cwd, argc, argv[0..argc) (argv[0] is the rustc path Cargo passed as the
 *   wrapper's first argument), envc, env[0..envc) as `NAME=value`. Sent once, right after connecting
 *   and checking SO_PEERCRED uid 0.
 * - `child` attempt, pid, starttime. `attempt` counts from 1 for this registration; `starttime` is
 *   field 22 of /proc/<pid>/stat. Sent after fork, while the child is blocked on the release pipe.
 * - `exited` attempt, wait_status (the raw waitpid status). Sent after reaping the child, once the
 *   child's standard error has reached EOF and been relayed.
 *
 * ## Controller -> wrapper
 * - `release` attempt: the child may exec rustc.
 * - `abort` attempt: only before `release`; the wrapper sends SIGKILL to the child, reaps it, and
 *   sends `exited` as usual.
 * - `retry`: only after `exited`; the wrapper discards the incomplete trailing standard-error line of
 *   the finished attempt and forks attempt+1.
 * - `final` status (0..255): only after `exited`; the wrapper exits with that status.
 *
 * ## Child
 * The child keeps descriptors 0 and 1, gets the write end of a fresh standard-error pipe as 2, keeps
 * the jobserver descriptors named by `--jobserver-auth=R,W` / `--jobserver-fds=R,W` in MAKEFLAGS or
 * CARGO_MAKEFLAGS (no descriptors for `fifo:PATH`), and closes every other descriptor before execve.
 * It blocks reading one byte from the release pipe; EOF without a byte means exit 125 without exec.
 * It execs argv with env and cwd exactly as registered.
 *
 * ## Standard error relay
 * Same semantics as CompilerErrorRelay: complete lines are forwarded as they arrive; a line that
 * starts with `{"$message_type":"artifact"` is forwarded only the first time its exact bytes are seen
 * for this registration; on `retry` the incomplete trailing line is discarded; on `final` it is
 * flushed.
 *
 * ## Failure
 * If the controller connection reaches EOF, errors, or a read exceeds timeout_millis, the wrapper
 * sends SIGKILL to any live child, reaps it, writes a diagnostic to stderr and exits 125. The wrapper
 * never exits while its child is unreaped, so the child's pid cannot be reused while the controller
 * may still act on it.
 *
 * # Details this program fixes within the protocol
 *
 * The timeout of a controller read runs from the moment the wrapper starts
 * waiting for a message until the message's last byte arrives. The same limit
 * bounds sending one message and connecting. While a released attempt runs,
 * the wrapper reads nothing; any message, EOF or error on the connection in
 * that interval ends the wrapper as a failure. The wrapper keeps the exited
 * child unreaped until its standard error reaches EOF, then reaps it and sends
 * `exited`. The controller must therefore make every process that inherited
 * that standard error exit; the wrapper waits for EOF without a time limit.
 * The child executes argv[0] as a path, without a PATH search. SIGINT, SIGTERM
 * and SIGHUP that Cargo did not ignore make the wrapper fail the same way as a
 * lost connection, so that a signal cannot end it while its child is unreaped;
 * the child restores Cargo's original signal dispositions and mask before it
 * blocks on the release pipe.
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <limits.h>
#include <poll.h>
#include <signal.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/time.h>
#include <sys/types.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#if defined(CODEX_TEST_TRUSTED_DIRECTORY) && !defined(CODEX_TEST_ALLOW_NONROOT_CONTROLLER)
#error CODEX_TEST_TRUSTED_DIRECTORY applies only to a CODEX_TEST_ALLOW_NONROOT_CONTROLLER build
#endif

/* The generic syscall table gives both calls the same number on every Linux architecture. */
#ifndef SYS_pidfd_open
#define SYS_pidfd_open 434
#endif
#ifndef SYS_close_range
#define SYS_close_range 436
#endif

/** Environment variable that names the root-controlled configuration file. */
#define CONFIG_ENV "CODEX_LINUX_CARGO_WRAPPER_CONFIG"
/** Exit status of every configuration, protocol, system-call and controller failure. */
#define FAILURE_STATUS 125
/** Largest frame payload either side may send. */
#define MAX_FRAME_BYTES 1048576U
/** Largest configuration file; the three entries need far less. */
#define MAX_CONFIG_BYTES 4096
/** Largest timeout_millis, so that adding it to a monotonic reading cannot overflow. */
#define MAX_TIMEOUT_MILLIS ((uint64_t) INT64_MAX / 4)
/** Most jobserver descriptor numbers that the make flags may name. */
#define MAX_KEPT_DESCRIPTORS 32
/** Bytes read from the attempt's standard-error pipe at a time. */
#define RELAY_CHUNK_BYTES 65536
/** Prefix of the rustc artifact notification that Cargo accepts only once per compiler invocation. */
#define ARTIFACT_PREFIX "{\"$message_type\":\"artifact\""

/** Signals that end the wrapper through its cleanup path unless Cargo ignored them. */
static const int TERMINATION_SIGNALS[] = { SIGINT, SIGTERM, SIGHUP };
#define TERMINATION_SIGNAL_COUNT (sizeof(TERMINATION_SIGNALS) / sizeof(TERMINATION_SIGNALS[0]))

/** Signal that requested termination, or zero; the control loop performs the cleanup. */
static volatile sig_atomic_t termination_signal;

/** A growable byte sequence. */
struct buffer {
    char *bytes;
    size_t length;
    size_t capacity;
};

/** Standard-error relay state that persists across one registration's attempts. */
struct relay {
    /** Output of the current attempt after its last newline. */
    struct buffer line;
    /** Artifact notification lines already forwarded, each including its newline if any. */
    struct buffer *artifacts;
    size_t artifact_count;
    size_t artifact_capacity;
};

/** Signal state that Cargo gave the wrapper and that each child restores before exec. */
struct inherited_signals {
    sigset_t mask;
    struct sigaction pipe_action;
    struct sigaction termination_actions[TERMINATION_SIGNAL_COUNT];
};

/** Owns the controller connection and the current attempt's child and pipes. */
struct wrapper {
    int socket;
    uint64_t timeout_millis;
    /** Compiler path followed by its arguments, NULL-terminated, borrowed from main's argv. */
    char **compiler;
    int compiler_count;
    uint64_t attempt;
    /** Current child, unreaped while positive. */
    pid_t child;
    int child_descriptor;
    /** Write end of the current child's release pipe, or -1 once released or aborted. */
    int release;
    /** Read end of the current child's standard-error pipe, or -1 after EOF. */
    int errors;
    /** Jobserver descriptor numbers that every child keeps open. */
    int kept[MAX_KEPT_DESCRIPTORS];
    size_t kept_count;
    struct inherited_signals signals;
    struct relay relay;
};

/** Commands that the controller sends. */
enum command_kind {
    COMMAND_RELEASE,
    COMMAND_ABORT,
    COMMAND_RETRY,
    COMMAND_FINAL
};

/** One parsed controller command; value is the attempt or the final status. */
struct command {
    enum command_kind kind;
    uint64_t value;
};

/** Records a termination request; the control loop performs cleanup. */
static void request_termination(int signal_number)
{
    termination_signal = signal_number;
}

/** Reports a failed operation with its errno and returns -1. */
static int fail(const char *operation)
{
    int failure_number = errno;
    fprintf(stderr, "linux-cargo-wrapper: %s: %s\n", operation, strerror(failure_number));
    return -1;
}

/** Reports an invalid input, configuration or protocol state and returns -1. */
static int reject(const char *reason)
{
    fprintf(stderr, "linux-cargo-wrapper: %s\n", reason);
    return -1;
}

/** Reports a received termination signal and returns -1. */
static int reject_termination(void)
{
    fprintf(stderr, "linux-cargo-wrapper: received signal %d\n", (int) termination_signal);
    return -1;
}

/** Returns monotonic milliseconds, or -1 after reporting clock failure. */
static int64_t monotonic_milliseconds(void)
{
    struct timespec observed;
    if (clock_gettime(CLOCK_MONOTONIC, &observed) < 0)
        return fail("read monotonic clock");
    return ((int64_t) observed.tv_sec * 1000) + (observed.tv_nsec / 1000000);
}

/**
 * Parses length bytes of canonical unsigned decimal, without sign or leading
 * zeros, bounded by maximum. Returns zero, or -1 without a diagnostic.
 */
static int parse_decimal(const char *text, size_t length, uint64_t maximum, uint64_t *value)
{
    if (length == 0 || (text[0] == '0' && length > 1))
        return -1;
    uint64_t result = 0;
    for (size_t index = 0; index < length; ++index) {
        if (text[index] < '0' || text[index] > '9')
            return -1;
        uint64_t digit = (uint64_t) (text[index] - '0');
        if (result > (maximum - digit) / 10)
            return -1;
        result = (result * 10) + digit;
    }
    *value = result;
    return 0;
}

/**
 * Moves a new close-on-exec descriptor above standard error. When Cargo closed
 * descriptor 0, 1 or 2, a new descriptor would otherwise take that number, so
 * wrapper diagnostics would reach it and the child could not keep the closed
 * standard descriptor closed. Returns the descriptor, or -1 after closing it.
 */
static int above_standard(int descriptor)
{
    if (descriptor > STDERR_FILENO)
        return descriptor;
    int moved = fcntl(descriptor, F_DUPFD_CLOEXEC, STDERR_FILENO + 1);
    int failure_number = errno;
    close(descriptor);
    if (moved < 0) {
        errno = failure_number;
        return fail("move a descriptor above standard error");
    }
    return moved;
}

/** Closes a descriptor that the wrapper owns, if any, and marks it closed. */
static void close_owned(int *descriptor)
{
    if (*descriptor >= 0) {
        close(*descriptor);
        *descriptor = -1;
    }
}

/** Appends bytes to a buffer. Returns zero, or -1 after reporting allocation failure. */
static int buffer_append(struct buffer *buffer, const char *bytes, size_t length)
{
    if (length > SIZE_MAX - buffer->length) {
        errno = ENOMEM;
        return fail("grow a buffer");
    }
    size_t required = buffer->length + length;
    if (required > buffer->capacity) {
        size_t capacity = buffer->capacity == 0 ? 256 : buffer->capacity;
        while (capacity < required)
            capacity = capacity > SIZE_MAX / 2 ? required : capacity * 2;
        char *grown = realloc(buffer->bytes, capacity);
        if (grown == NULL)
            return fail("grow a buffer");
        buffer->bytes = grown;
        buffer->capacity = capacity;
    }
    if (length > 0)
        memcpy(buffer->bytes + buffer->length, bytes, length);
    buffer->length = required;
    return 0;
}

/**
 * Checks that a configuration path component cannot be replaced by the build
 * user: owned by uid 0 and not group- or other-writable. The test build also
 * accepts the wrapper's own uid, and checks only the trusted directory and its
 * descendants. Returns zero, or -1 with a diagnostic.
 */
static int require_root_controlled(int descriptor, const char *path)
{
#ifdef CODEX_TEST_TRUSTED_DIRECTORY
    size_t trusted_length = strlen(CODEX_TEST_TRUSTED_DIRECTORY);
    bool below_trusted = strncmp(path, CODEX_TEST_TRUSTED_DIRECTORY, trusted_length) == 0 &&
        (path[trusted_length] == '\0' || path[trusted_length] == '/');
    if (!below_trusted)
        return 0;
#endif
    struct stat metadata;
    if (fstat(descriptor, &metadata) < 0)
        return fail("inspect configuration path component");
    bool trusted_owner = metadata.st_uid == 0;
#ifdef CODEX_TEST_ALLOW_NONROOT_CONTROLLER
    trusted_owner = trusted_owner || metadata.st_uid == geteuid();
#endif
    if (!trusted_owner || (metadata.st_mode & (S_IWGRP | S_IWOTH)) != 0) {
        fprintf(stderr, "linux-cargo-wrapper: configuration path %s must be owned by uid 0 and not "
            "group- or other-writable\n", path);
        return -1;
    }
    return 0;
}

/**
 * Opens the configuration file by walking its absolute normalized path from /
 * without following any symbolic link, checking each directory and the file.
 * Returns the open file, or -1 with a diagnostic.
 */
static int open_config(const char *path)
{
    size_t path_length = strlen(path);
    if (path[0] != '/' || path_length >= PATH_MAX)
        return reject(CONFIG_ENV " must name an absolute path shorter than PATH_MAX");
    char prefix[PATH_MAX] = "/";
    size_t prefix_length = 1;
    int directory = open("/", O_PATH | O_DIRECTORY | O_CLOEXEC);
    if (directory < 0)
        return fail("open the root directory");
    if (require_root_controlled(directory, prefix) < 0) {
        close(directory);
        return -1;
    }
    const char *component = path + 1;
    for (;;) {
        const char *separator = strchr(component, '/');
        size_t length = separator == NULL ? strlen(component) : (size_t) (separator - component);
        bool dot_component = (length == 1 && component[0] == '.') ||
            (length == 2 && component[0] == '.' && component[1] == '.');
        if (length == 0 || length > NAME_MAX || dot_component) {
            close(directory);
            return reject(CONFIG_ENV " must name a normalized path to a file");
        }
        char name[NAME_MAX + 1];
        memcpy(name, component, length);
        name[length] = '\0';
        if (prefix_length > 1)
            prefix[prefix_length++] = '/';
        memcpy(prefix + prefix_length, name, length + 1);
        prefix_length += length;

        int flags = O_PATH | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC;
        if (separator == NULL)
            flags = O_RDONLY | O_NOFOLLOW | O_NOCTTY | O_CLOEXEC;
        int next = openat(directory, name, flags);
        int open_error = errno;
        close(directory);
        if (next < 0) {
            errno = open_error;
            fprintf(stderr, "linux-cargo-wrapper: open configuration path %s without following symbolic "
                "links: %s\n", prefix, strerror(open_error));
            return -1;
        }
        if (require_root_controlled(next, prefix) < 0) {
            close(next);
            return -1;
        }
        if (separator == NULL) {
            struct stat metadata;
            if (fstat(next, &metadata) < 0 || !S_ISREG(metadata.st_mode)) {
                close(next);
                return reject("the configuration must be a regular file");
            }
            return next;
        }
        directory = next;
        component = separator + 1;
    }
}

/**
 * Matches one exact `key=value\n` line at the cursor and advances past it.
 * Returns the value's start and length, or NULL when the line does not match.
 */
static const char *config_value(const char **cursor, const char *end, const char *key, size_t *length)
{
    size_t key_length = strlen(key);
    if ((size_t) (end - *cursor) <= key_length || memcmp(*cursor, key, key_length) != 0 ||
        (*cursor)[key_length] != '=')
        return NULL;
    const char *value = *cursor + key_length + 1;
    const char *newline = memchr(value, '\n', (size_t) (end - value));
    if (newline == NULL)
        return NULL;
    *length = (size_t) (newline - value);
    *cursor = newline + 1;
    return value;
}

/**
 * Reads and validates the configuration that CODEX_LINUX_CARGO_WRAPPER_CONFIG
 * names. Returns zero with the controller path copied into address, or -1.
 */
static int read_config(struct sockaddr_un *address, uint64_t *timeout_millis)
{
    const char *path = getenv(CONFIG_ENV);
    if (path == NULL || path[0] == '\0')
        return reject(CONFIG_ENV " is not set");
    int file = open_config(path);
    if (file < 0)
        return -1;
    char contents[MAX_CONFIG_BYTES + 1];
    size_t length = 0;
    for (;;) {
        ssize_t count = read(file, contents + length, sizeof(contents) - length);
        if (count < 0 && errno == EINTR)
            continue;
        if (count < 0) {
            int failure_number = errno;
            close(file);
            errno = failure_number;
            return fail("read the configuration");
        }
        if (count == 0)
            break;
        length += (size_t) count;
        if (length == sizeof(contents)) {
            close(file);
            return reject("the configuration exceeds 4096 bytes");
        }
    }
    close(file);

    for (size_t index = 0; index < length; ++index) {
        unsigned char character = (unsigned char) contents[index];
        if (character != '\n' && (character < 32 || character > 126))
            return reject("the configuration contains a byte that is not printable ASCII or a newline");
    }
    const char *cursor = contents;
    const char *end = contents + length;
    size_t value_length;
    const char *version = config_value(&cursor, end, "version", &value_length);
    if (version == NULL || value_length != 1 || version[0] != '2')
        return reject("the configuration's first line must be version=2");
    const char *controller = config_value(&cursor, end, "controller", &value_length);
    if (controller == NULL || value_length == 0 || controller[0] != '/' ||
        value_length >= sizeof(address->sun_path))
        return reject("the configuration's second line must be controller=<absolute socket path shorter than "
            "108 bytes>");
    memset(address, 0, sizeof(*address));
    address->sun_family = AF_UNIX;
    memcpy(address->sun_path, controller, value_length);
    const char *timeout = config_value(&cursor, end, "timeout_millis", &value_length);
    if (timeout == NULL || parse_decimal(timeout, value_length, MAX_TIMEOUT_MILLIS, timeout_millis) < 0 ||
        *timeout_millis == 0)
        return reject("the configuration's third line must be timeout_millis=<positive decimal>");
    if (cursor != end)
        return reject("the configuration has content after timeout_millis");
    return 0;
}

/**
 * Records the descriptor numbers that `--jobserver-auth=R,W` and
 * `--jobserver-fds=R,W` words in one make-flags value name. A `fifo:PATH`
 * value, and a value that names no two descriptors, add nothing. Returns zero,
 * or -1 when more numbers are named than the wrapper can keep.
 */
static int add_jobserver_descriptors(struct wrapper *wrapper, const char *flags)
{
    static const char *const options[] = { "--jobserver-auth=", "--jobserver-fds=" };
    const char *word = flags;
    while (*word != '\0') {
        while (*word == ' ' || *word == '\t')
            ++word;
        size_t word_length = strcspn(word, " \t");
        for (size_t index = 0; index < sizeof(options) / sizeof(options[0]); ++index) {
            size_t option_length = strlen(options[index]);
            if (word_length <= option_length || strncmp(word, options[index], option_length) != 0)
                continue;
            const char *value = word + option_length;
            size_t value_length = word_length - option_length;
            const char *comma = memchr(value, ',', value_length);
            uint64_t read_end;
            uint64_t write_end;
            if (comma == NULL ||
                parse_decimal(value, (size_t) (comma - value), INT_MAX, &read_end) < 0 ||
                parse_decimal(comma + 1, value_length - (size_t) (comma + 1 - value), INT_MAX, &write_end) < 0)
                continue;
            int numbers[] = { (int) read_end, (int) write_end };
            for (size_t number = 0; number < 2; ++number) {
                bool known = false;
                for (size_t kept = 0; kept < wrapper->kept_count; ++kept)
                    known = known || wrapper->kept[kept] == numbers[number];
                if (known)
                    continue;
                if (wrapper->kept_count == MAX_KEPT_DESCRIPTORS)
                    return reject("the make flags name more than 32 jobserver descriptors");
                wrapper->kept[wrapper->kept_count++] = numbers[number];
            }
        }
        word += word_length;
    }
    return 0;
}

/**
 * Saves Cargo's signal state for each child to restore, ignores SIGPIPE so that
 * a closed Cargo stderr is reported as a write error, and blocks the
 * termination signals that Cargo did not ignore so that they are delivered only
 * inside ppoll. Returns zero, or -1.
 */
static int install_signals(struct wrapper *wrapper)
{
    struct inherited_signals *signals = &wrapper->signals;
    struct sigaction ignore = { .sa_handler = SIG_IGN };
    sigemptyset(&ignore.sa_mask);
    if (sigaction(SIGPIPE, &ignore, &signals->pipe_action) < 0)
        return fail("ignore SIGPIPE");
    sigset_t blocked;
    sigemptyset(&blocked);
    struct sigaction handler = { .sa_handler = request_termination };
    sigemptyset(&handler.sa_mask);
    for (size_t index = 0; index < TERMINATION_SIGNAL_COUNT; ++index) {
        int signal_number = TERMINATION_SIGNALS[index];
        if (sigaction(signal_number, NULL, &signals->termination_actions[index]) < 0)
            return fail("inspect a termination signal");
        if (signals->termination_actions[index].sa_handler == SIG_IGN)
            continue;
        if (sigaction(signal_number, &handler, NULL) < 0)
            return fail("handle a termination signal");
        sigaddset(&blocked, signal_number);
    }
    if (sigprocmask(SIG_BLOCK, &blocked, &signals->mask) < 0)
        return fail("block termination signals");
    return 0;
}

/**
 * Connects to the controller within timeout_millis and checks with SO_PEERCRED
 * that its peer is uid 0. Returns zero with the socket in wrapper, or -1.
 */
static int connect_controller(struct wrapper *wrapper, const struct sockaddr_un *address)
{
    wrapper->socket = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (wrapper->socket < 0)
        return fail("create the controller socket");
    wrapper->socket = above_standard(wrapper->socket);
    if (wrapper->socket < 0)
        return -1;
    /* A Unix stream connect waits for a full listen backlog for at most the send timeout. */
    struct timeval limit = {
        .tv_sec = (time_t) (wrapper->timeout_millis / 1000),
        .tv_usec = (suseconds_t) ((wrapper->timeout_millis % 1000) * 1000)
    };
    if (setsockopt(wrapper->socket, SOL_SOCKET, SO_SNDTIMEO, &limit, sizeof(limit)) < 0)
        return fail("limit the controller connection time");
    if (connect(wrapper->socket, (const struct sockaddr *) address, sizeof(*address)) < 0)
        return fail("connect to the controller");
    struct ucred credentials;
    socklen_t credentials_length = sizeof(credentials);
    if (getsockopt(wrapper->socket, SOL_SOCKET, SO_PEERCRED, &credentials, &credentials_length) < 0)
        return fail("read the controller's peer credentials");
    if (credentials_length != sizeof(credentials))
        return reject("the controller's peer credentials have an unexpected size");
    bool trusted_peer = credentials.uid == 0;
#ifdef CODEX_TEST_ALLOW_NONROOT_CONTROLLER
    trusted_peer = trusted_peer || credentials.uid == geteuid();
#endif
    if (!trusted_peer)
        return reject("the controller's peer credentials do not identify uid 0");
    int flags = fcntl(wrapper->socket, F_GETFL);
    if (flags < 0 || fcntl(wrapper->socket, F_SETFL, flags | O_NONBLOCK) < 0)
        return fail("make the controller socket nonblocking");
    return 0;
}

/**
 * Waits until the controller socket reports events or a hangup or error,
 * before the absolute monotonic deadline. Termination signals are delivered
 * only here. Returns zero, or -1 with a diagnostic naming the operation.
 */
static int await_controller(struct wrapper *wrapper, short events, int64_t deadline, const char *operation)
{
    for (;;) {
        if (termination_signal != 0)
            return reject_termination();
        int64_t now = monotonic_milliseconds();
        if (now < 0)
            return -1;
        int64_t remaining = deadline - now;
        if (remaining <= 0) {
            fprintf(stderr, "linux-cargo-wrapper: controller %s exceeded timeout_millis\n", operation);
            return -1;
        }
        struct timespec limit = { .tv_sec = remaining / 1000, .tv_nsec = (remaining % 1000) * 1000000 };
        struct pollfd descriptor = { .fd = wrapper->socket, .events = events };
        int result = ppoll(&descriptor, 1, &limit, &wrapper->signals.mask);
        if (result < 0 && errno == EINTR)
            continue;
        if (result < 0)
            return fail("poll the controller connection");
        if ((descriptor.revents & POLLNVAL) != 0)
            return reject("the controller socket is not open");
        /* A hangup or error is reported by the following recv or send. */
        if (descriptor.revents != 0)
            return 0;
    }
}

/**
 * Sends one frame whose payload holds the given NUL-terminated fields, within
 * timeout_millis. Returns zero, or -1; a partly sent frame is never resent.
 */
static int send_message(struct wrapper *wrapper, const char *const *fields, size_t count)
{
    struct buffer frame = { 0 };
    int outcome = buffer_append(&frame, "\0\0\0\0", 4);
    for (size_t index = 0; outcome == 0 && index < count; ++index)
        outcome = buffer_append(&frame, fields[index], strlen(fields[index]) + 1);
    if (outcome < 0) {
        free(frame.bytes);
        return -1;
    }
    size_t length = frame.length - 4;
    if (length > MAX_FRAME_BYTES) {
        free(frame.bytes);
        fprintf(stderr, "linux-cargo-wrapper: the %s message exceeds 1048576 bytes\n", fields[0]);
        return -1;
    }
    frame.bytes[0] = (char) (length >> 24);
    frame.bytes[1] = (char) (length >> 16);
    frame.bytes[2] = (char) (length >> 8);
    frame.bytes[3] = (char) length;

    int64_t now = monotonic_milliseconds();
    if (now < 0) {
        free(frame.bytes);
        return -1;
    }
    int64_t deadline = now + (int64_t) wrapper->timeout_millis;
    size_t sent = 0;
    while (sent < frame.length) {
        if (await_controller(wrapper, POLLOUT, deadline, "write") < 0) {
            free(frame.bytes);
            return -1;
        }
        ssize_t result = send(wrapper->socket, frame.bytes + sent, frame.length - sent, MSG_NOSIGNAL);
        if (result < 0 && (errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK))
            continue;
        if (result < 0) {
            free(frame.bytes);
            return fail("send to the controller");
        }
        sent += (size_t) result;
    }
    free(frame.bytes);
    return 0;
}

/**
 * Receives exactly length bytes before the deadline. Returns zero, or -1 for
 * EOF, connection error or timeout.
 */
static int receive_bytes(struct wrapper *wrapper, unsigned char *bytes, size_t length, int64_t deadline)
{
    size_t received = 0;
    while (received < length) {
        if (await_controller(wrapper, POLLIN, deadline, "read") < 0)
            return -1;
        ssize_t result = recv(wrapper->socket, bytes + received, length - received, 0);
        if (result < 0 && (errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK))
            continue;
        if (result < 0)
            return fail("read from the controller");
        if (result == 0)
            return reject("the controller connection reached EOF");
        received += (size_t) result;
    }
    return 0;
}

/**
 * Receives one controller command within timeout_millis of starting to wait
 * and parses its type, field count and number. Returns zero, or -1.
 */
static int receive_command(struct wrapper *wrapper, struct command *command)
{
    int64_t now = monotonic_milliseconds();
    if (now < 0)
        return -1;
    int64_t deadline = now + (int64_t) wrapper->timeout_millis;
    unsigned char header[4];
    if (receive_bytes(wrapper, header, sizeof(header), deadline) < 0)
        return -1;
    uint32_t length = ((uint32_t) header[0] << 24) | ((uint32_t) header[1] << 16) |
        ((uint32_t) header[2] << 8) | header[3];
    if (length == 0 || length > MAX_FRAME_BYTES)
        return reject("the controller sent a frame length outside 1 through 1048576");
    unsigned char *payload = malloc(length);
    if (payload == NULL)
        return fail("allocate a controller frame");
    if (receive_bytes(wrapper, payload, length, deadline) < 0) {
        free(payload);
        return -1;
    }

    const char *fields[2];
    size_t count = 0;
    int outcome = 0;
    if (payload[length - 1] != '\0')
        outcome = reject("the controller sent a field without its NUL terminator");
    for (uint32_t start = 0; outcome == 0 && start < length; ++count) {
        if (count == 2) {
            outcome = reject("the controller sent a message with too many fields");
            break;
        }
        fields[count] = (const char *) payload + start;
        start += (uint32_t) strlen(fields[count]) + 1;
    }
    if (outcome == 0) {
        bool numbered = count == 2;
        uint64_t maximum = UINT64_MAX;
        if (strcmp(fields[0], "release") == 0 && numbered)
            command->kind = COMMAND_RELEASE;
        else if (strcmp(fields[0], "abort") == 0 && numbered)
            command->kind = COMMAND_ABORT;
        else if (strcmp(fields[0], "final") == 0 && numbered) {
            command->kind = COMMAND_FINAL;
            maximum = 255;
        } else if (strcmp(fields[0], "retry") == 0 && count == 1)
            command->kind = COMMAND_RETRY;
        else
            outcome = reject("the controller sent an unknown message type or a wrong field count");
        command->value = 0;
        if (outcome == 0 && numbered &&
            parse_decimal(fields[1], strlen(fields[1]), maximum, &command->value) < 0)
            outcome = reject("the controller sent a malformed or out-of-range number");
    }
    free(payload);
    return outcome;
}

/** Formats an unsigned decimal into a caller buffer of at least 21 bytes. */
static const char *decimal(uint64_t value, char *text)
{
    snprintf(text, 21, "%" PRIu64, value);
    return text;
}

/**
 * Sends the registration: protocol version, working directory, compiler argv
 * and the complete environment. Returns zero, or -1.
 */
static int send_registration(struct wrapper *wrapper)
{
    char *directory = getcwd(NULL, 0);
    if (directory == NULL)
        return fail("read the working directory");
    size_t environment_count = 0;
    while (environ[environment_count] != NULL)
        ++environment_count;
    size_t count = 5 + (size_t) wrapper->compiler_count + environment_count;
    const char **fields = calloc(count, sizeof(*fields));
    if (fields == NULL) {
        free(directory);
        return fail("allocate the registration");
    }
    char argument_count[21];
    char environment_size[21];
    size_t next = 0;
    fields[next++] = "register";
    fields[next++] = "2";
    fields[next++] = directory;
    fields[next++] = decimal((uint64_t) wrapper->compiler_count, argument_count);
    for (int index = 0; index < wrapper->compiler_count; ++index)
        fields[next++] = wrapper->compiler[index];
    fields[next++] = decimal(environment_count, environment_size);
    for (size_t index = 0; index < environment_count; ++index)
        fields[next++] = environ[index];
    int outcome = send_message(wrapper, fields, count);
    free(fields);
    free(directory);
    return outcome;
}

/**
 * Reads field 22, the start time, of /proc/PID/stat without treating spaces or
 * ')' in the command name as field delimiters. PID must be an unreaped child.
 * Returns zero, or -1.
 */
static int read_start_time(pid_t process, uint64_t *start_time)
{
    char path[64];
    snprintf(path, sizeof(path), "/proc/%ld/stat", (long) process);
    int descriptor = open(path, O_RDONLY | O_CLOEXEC);
    if (descriptor < 0)
        return fail("open the child's /proc stat");
    char contents[8192];
    ssize_t length;
    do {
        length = read(descriptor, contents, sizeof(contents) - 1);
    } while (length < 0 && errno == EINTR);
    int read_error = errno;
    close(descriptor);
    if (length < 0) {
        errno = read_error;
        return fail("read the child's /proc stat");
    }
    if (length == 0 || (size_t) length == sizeof(contents) - 1)
        return reject("the child's /proc stat is empty or exceeds 8191 bytes");
    contents[length] = '\0';
    char *closing = strrchr(contents, ')');
    if (closing == NULL || closing[1] != ' ')
        return reject("the child's /proc stat has no complete command field");
    char *cursor = closing + 2;
    for (int field = 3; field < 22; ++field) {
        char *separator = strchr(cursor, ' ');
        if (separator == NULL)
            return reject("the child's /proc stat has no start time field");
        cursor = separator + 1;
    }
    size_t field_length = strcspn(cursor, " \n");
    if (parse_decimal(cursor, field_length, UINT64_MAX, start_time) < 0)
        return reject("the child's /proc stat start time is malformed");
    return 0;
}

/** Compares integers for qsort. */
static int compare_descriptors(const void *left, const void *right)
{
    int first = *(const int *) left;
    int second = *(const int *) right;
    return (first > second) - (first < second);
}

/**
 * Closes every descriptor above standard error except the kept numbers, so the
 * compiler inherits no descriptor that Cargo or the wrapper opened by accident.
 * Returns zero, or -1.
 */
static int close_unkept_descriptors(const int *kept, size_t count)
{
    int sorted[MAX_KEPT_DESCRIPTORS + 1];
    memcpy(sorted, kept, count * sizeof(*kept));
    qsort(sorted, count, sizeof(*sorted), compare_descriptors);
    unsigned int first = STDERR_FILENO + 1;
    for (size_t index = 0; index < count; ++index) {
        if (sorted[index] < (int) first)
            continue;
        if ((unsigned int) sorted[index] > first &&
            syscall(SYS_close_range, first, (unsigned int) sorted[index] - 1, 0U) < 0)
            return -1;
        first = (unsigned int) sorted[index] + 1;
    }
    if (first != 0 && syscall(SYS_close_range, first, ~0U, 0U) < 0)
        return -1;
    return 0;
}

/**
 * Runs in the forked child: restores Cargo's signal state, installs the
 * standard-error pipe, closes every other descriptor, waits for the release
 * byte and executes the compiler. Never returns.
 */
static void run_child(struct wrapper *wrapper, int release, int errors)
{
    sigaction(SIGPIPE, &wrapper->signals.pipe_action, NULL);
    for (size_t index = 0; index < TERMINATION_SIGNAL_COUNT; ++index)
        sigaction(TERMINATION_SIGNALS[index], &wrapper->signals.termination_actions[index], NULL);
    if (sigprocmask(SIG_SETMASK, &wrapper->signals.mask, NULL) < 0)
        _exit(FAILURE_STATUS);
    if (dup2(errors, STDERR_FILENO) < 0)
        _exit(FAILURE_STATUS);
    int kept[MAX_KEPT_DESCRIPTORS + 1];
    memcpy(kept, wrapper->kept, wrapper->kept_count * sizeof(*kept));
    kept[wrapper->kept_count] = release;
    if (close_unkept_descriptors(kept, wrapper->kept_count + 1) < 0) {
        fail("close inherited descriptors");
        _exit(FAILURE_STATUS);
    }

    unsigned char permission;
    ssize_t result;
    do {
        result = read(release, &permission, 1);
    } while (result < 0 && errno == EINTR);
    if (result != 1)
        _exit(FAILURE_STATUS);
    close(release);
    execve(wrapper->compiler[0], wrapper->compiler, environ);
    int failure_number = errno;
    fprintf(stderr, "linux-cargo-wrapper: execute %s: %s\n", wrapper->compiler[0], strerror(failure_number));
    _exit(failure_number == ENOENT ? 127 : 126);
}

/**
 * Forks the next attempt's child, blocked on its release pipe, and sends the
 * controller its pid and start time. Returns zero with the child and its pipes
 * owned by wrapper, or -1; an unreaped child remains owned for cleanup.
 */
static int start_attempt(struct wrapper *wrapper)
{
    int release[2] = { -1, -1 };
    int errors[2] = { -1, -1 };
    if (pipe2(release, O_CLOEXEC) < 0)
        return fail("create the release pipe");
    if (pipe2(errors, O_CLOEXEC) < 0) {
        fail("create the standard-error pipe");
        close_owned(&release[0]);
        close_owned(&release[1]);
        return -1;
    }
    for (size_t index = 0; index < 2; ++index) {
        release[index] = above_standard(release[index]);
        errors[index] = above_standard(errors[index]);
    }
    if (release[0] < 0 || release[1] < 0 || errors[0] < 0 || errors[1] < 0) {
        for (size_t index = 0; index < 2; ++index) {
            close_owned(&release[index]);
            close_owned(&errors[index]);
        }
        return -1;
    }
    pid_t child = fork();
    if (child == 0)
        run_child(wrapper, release[0], errors[1]);
    int fork_error = errno;
    close_owned(&release[0]);
    close_owned(&errors[1]);
    if (child < 0) {
        close_owned(&release[1]);
        close_owned(&errors[0]);
        errno = fork_error;
        return fail("fork the attempt's child");
    }
    wrapper->child = child;
    wrapper->release = release[1];
    wrapper->errors = errors[0];
    int flags = fcntl(wrapper->errors, F_GETFL);
    if (flags < 0 || fcntl(wrapper->errors, F_SETFL, flags | O_NONBLOCK) < 0)
        return fail("make the standard-error pipe nonblocking");
    /* The child is unreaped, so its pid cannot denote another process here. */
    wrapper->child_descriptor = (int) syscall(SYS_pidfd_open, child, 0U);
    if (wrapper->child_descriptor < 0)
        return fail("open a pidfd for the child");
    wrapper->child_descriptor = above_standard(wrapper->child_descriptor);
    if (wrapper->child_descriptor < 0)
        return -1;

    uint64_t start_time;
    if (read_start_time(child, &start_time) < 0)
        return -1;
    char attempt[21];
    char pid[21];
    char start[21];
    const char *fields[] = {
        "child", decimal(wrapper->attempt, attempt), decimal((uint64_t) child, pid), decimal(start_time, start)
    };
    return send_message(wrapper, fields, 4);
}

/** Writes all bytes to the wrapper's standard error. Returns zero, or -1. */
static int write_stderr(const char *bytes, size_t length)
{
    size_t written = 0;
    while (written < length) {
        ssize_t result = write(STDERR_FILENO, bytes + written, length - written);
        if (result < 0 && errno == EINTR)
            continue;
        if (result < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
            /* Cargo may share a nonblocking pipe; wait until it drains. */
            struct pollfd descriptor = { .fd = STDERR_FILENO, .events = POLLOUT };
            if (poll(&descriptor, 1, -1) < 0 && errno != EINTR)
                return fail("wait for Cargo's standard error");
            continue;
        }
        if (result < 0)
            return fail("relay the compiler's standard error to Cargo");
        written += (size_t) result;
    }
    return 0;
}

/**
 * Forwards the held line unless it is an artifact notification whose exact
 * bytes were already forwarded, then clears it. Returns zero, or -1.
 */
static int relay_forward_line(struct relay *relay)
{
    struct buffer *line = &relay->line;
    size_t prefix_length = sizeof(ARTIFACT_PREFIX) - 1;
    bool artifact = line->length >= prefix_length && memcmp(line->bytes, ARTIFACT_PREFIX, prefix_length) == 0;
    if (artifact) {
        for (size_t index = 0; index < relay->artifact_count; ++index) {
            struct buffer *seen = &relay->artifacts[index];
            if (seen->length == line->length && memcmp(seen->bytes, line->bytes, line->length) == 0) {
                line->length = 0;
                return 0;
            }
        }
        if (relay->artifact_count == relay->artifact_capacity) {
            size_t capacity = relay->artifact_capacity == 0 ? 4 : relay->artifact_capacity * 2;
            struct buffer *grown = realloc(relay->artifacts, capacity * sizeof(*grown));
            if (grown == NULL)
                return fail("record a forwarded artifact notification");
            relay->artifacts = grown;
            relay->artifact_capacity = capacity;
        }
        struct buffer *seen = &relay->artifacts[relay->artifact_count];
        *seen = (struct buffer) { 0 };
        if (buffer_append(seen, line->bytes, line->length) < 0) {
            free(seen->bytes);
            return -1;
        }
        ++relay->artifact_count;
    }
    int outcome = write_stderr(line->bytes, line->length);
    line->length = 0;
    return outcome;
}

/** Accepts output that the current attempt wrote to its standard error. Returns zero, or -1. */
static int relay_accept(struct relay *relay, const char *bytes, size_t length)
{
    size_t start = 0;
    for (size_t index = 0; index < length; ++index) {
        if (bytes[index] != '\n')
            continue;
        if (buffer_append(&relay->line, bytes + start, index + 1 - start) < 0 || relay_forward_line(relay) < 0)
            return -1;
        start = index + 1;
    }
    return buffer_append(&relay->line, bytes + start, length - start);
}

/**
 * Ends the current attempt's output: a superseded attempt's incomplete last
 * line is discarded, and the accepted attempt's is forwarded. Returns zero, or -1.
 */
static int relay_end(struct relay *relay, bool superseded)
{
    if (superseded || relay->line.length == 0) {
        relay->line.length = 0;
        return 0;
    }
    return relay_forward_line(relay);
}

/**
 * Reports a controller connection event while no message may arrive: EOF,
 * an unexpected message or an error. Returns -1.
 */
static int reject_unexpected_controller_event(struct wrapper *wrapper, short revents)
{
    if ((revents & POLLIN) != 0) {
        char peeked;
        ssize_t result = recv(wrapper->socket, &peeked, 1, MSG_PEEK);
        if (result == 0)
            return reject("the controller connection reached EOF");
        if (result > 0)
            return reject("the controller sent a message while the attempt was running");
        if (errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR)
            return fail("read from the controller");
    }
    return reject("the controller connection failed");
}

/**
 * Relays the released or aborted child's standard error until EOF and waits
 * for the child to exit, then reaps it. The child stays unreaped until its
 * standard error has ended. Returns zero with the raw wait status, or -1.
 */
static int finish_attempt(struct wrapper *wrapper, int *wait_status)
{
    static char chunk[RELAY_CHUNK_BYTES];
    bool exited = false;
    while (wrapper->errors >= 0 || !exited) {
        if (termination_signal != 0)
            return reject_termination();
        struct pollfd descriptors[3] = {
            { .fd = wrapper->socket, .events = POLLIN },
            { .fd = wrapper->errors, .events = POLLIN },
            { .fd = exited ? -1 : wrapper->child_descriptor, .events = POLLIN }
        };
        int result = ppoll(descriptors, 3, NULL, &wrapper->signals.mask);
        if (result < 0 && errno == EINTR)
            continue;
        if (result < 0)
            return fail("poll the running attempt");
        if (descriptors[0].revents != 0)
            return reject_unexpected_controller_event(wrapper, descriptors[0].revents);
        if (descriptors[1].revents != 0) {
            ssize_t count = read(wrapper->errors, chunk, sizeof(chunk));
            if (count < 0 && errno != EINTR && errno != EAGAIN && errno != EWOULDBLOCK)
                return fail("read the compiler's standard error");
            if (count == 0)
                close_owned(&wrapper->errors);
            else if (count > 0 && relay_accept(&wrapper->relay, chunk, (size_t) count) < 0)
                return -1;
        }
        if (descriptors[2].revents != 0)
            exited = true;
    }
    pid_t reaped;
    do {
        reaped = waitpid(wrapper->child, wait_status, 0);
    } while (reaped < 0 && errno == EINTR);
    if (reaped < 0)
        return fail("reap the attempt's child");
    wrapper->child = 0;
    close_owned(&wrapper->child_descriptor);
    return 0;
}

/**
 * Runs one attempt from fork through the controller's decision after `exited`.
 * Returns 0 for retry, 1 with final_status for final, or -1.
 */
static int run_attempt(struct wrapper *wrapper, int *final_status)
{
    if (start_attempt(wrapper) < 0)
        return -1;
    struct command command;
    if (receive_command(wrapper, &command) < 0)
        return -1;
    bool current = command.value == wrapper->attempt;
    if (command.kind == COMMAND_RELEASE && current) {
        unsigned char permission = 1;
        ssize_t result;
        do {
            result = write(wrapper->release, &permission, 1);
        } while (result < 0 && errno == EINTR);
        /* EPIPE means the child already died; finish_attempt reports how. */
        if (result < 0 && errno != EPIPE)
            return fail("release the child");
    } else if (command.kind == COMMAND_ABORT && current) {
        if (kill(wrapper->child, SIGKILL) < 0)
            return fail("kill the aborted child");
    } else {
        return reject("the controller sent a message other than release or abort for the current attempt");
    }
    close_owned(&wrapper->release);

    int wait_status;
    if (finish_attempt(wrapper, &wait_status) < 0)
        return -1;
    char attempt[21];
    char status[21];
    const char *fields[] = {
        "exited", decimal(wrapper->attempt, attempt), decimal((uint64_t) (unsigned int) wait_status, status)
    };
    if (send_message(wrapper, fields, 3) < 0)
        return -1;
    if (receive_command(wrapper, &command) < 0)
        return -1;
    if (command.kind == COMMAND_RETRY) {
        if (wrapper->attempt == UINT64_MAX)
            return reject("the attempt number would overflow");
        ++wrapper->attempt;
        relay_end(&wrapper->relay, true);
        return 0;
    }
    if (command.kind != COMMAND_FINAL)
        return reject("the controller sent a message other than retry or final after exited");
    if (relay_end(&wrapper->relay, false) < 0)
        return -1;
    *final_status = (int) command.value;
    return 1;
}

/**
 * Kills and reaps an unreaped child and closes the wrapper's descriptors, so
 * that the wrapper never exits while its child's pid is reserved.
 */
static void abandon(struct wrapper *wrapper)
{
    if (wrapper->child > 0) {
        if (kill(wrapper->child, SIGKILL) < 0)
            fail("kill the child during cleanup");
        int status;
        pid_t reaped;
        do {
            reaped = waitpid(wrapper->child, &status, 0);
        } while (reaped < 0 && errno == EINTR);
        if (reaped < 0)
            fail("reap the child during cleanup");
        wrapper->child = 0;
    }
    close_owned(&wrapper->release);
    close_owned(&wrapper->errors);
    close_owned(&wrapper->child_descriptor);
    close_owned(&wrapper->socket);
}

/** Releases the relay's memory. */
static void free_relay(struct relay *relay)
{
    free(relay->line.bytes);
    for (size_t index = 0; index < relay->artifact_count; ++index)
        free(relay->artifacts[index].bytes);
    free(relay->artifacts);
}

/**
 * Validates the invocation and configuration, registers with the controller
 * and runs attempts until the controller sends the final status. Returns that
 * status, or 125 after reporting a failure.
 */
int main(int argc, char **argv)
{
    struct wrapper wrapper = {
        .socket = -1, .child_descriptor = -1, .release = -1, .errors = -1, .attempt = 1,
        .compiler = argv + 1, .compiler_count = argc - 1
    };
    if (argc < 2) {
        reject("expected the compiler path followed by its arguments");
        return FAILURE_STATUS;
    }
    struct sockaddr_un address;
    int outcome = 0;
    if (install_signals(&wrapper) < 0 || read_config(&address, &wrapper.timeout_millis) < 0)
        outcome = -1;
    const char *make_variables[] = { "MAKEFLAGS", "CARGO_MAKEFLAGS" };
    for (size_t index = 0; outcome == 0 && index < 2; ++index) {
        const char *flags = getenv(make_variables[index]);
        if (flags != NULL)
            outcome = add_jobserver_descriptors(&wrapper, flags);
    }
    if (outcome == 0 && (connect_controller(&wrapper, &address) < 0 || send_registration(&wrapper) < 0))
        outcome = -1;
    int final_status = FAILURE_STATUS;
    while (outcome == 0)
        outcome = run_attempt(&wrapper, &final_status);
    if (outcome < 0)
        final_status = FAILURE_STATUS;
    abandon(&wrapper);
    free_relay(&wrapper.relay);
    return final_status;
}
