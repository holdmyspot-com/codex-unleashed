/*
 * Preloaded allocation hook for one Linux compiler attempt, replacing per-process Frida injection.
 *
 * Loading: LD_PRELOAD names this library and CODEX_BUILD_HOOK names the attempt's Unix socket. The constructor
 * attaches the malloc hook, connects, sends hello and waits for welcome before returning, so main never runs
 * unhooked. Allocations made by ld.so, and by constructors that run before this one, are not covered.
 *
 * Wire: 4-byte big-endian payload length, then NUL-terminated fields; protocol version 1.
 * - Client: "hello" version pid start; "blocked" wait thread bytes errno; "granted" wait unknown|accepted|duplicate;
 *   "transfer" sequence read|write [byte]; "failure" sequence cause; "exec-static" path.
 * - Server: "welcome" installation; "grant" wait; "token" sequence [byte]; "adopt".
 * The start is field 22 of /proc/<pid>/stat. Numbers are canonical unsigned decimals.
 *
 * Allocation waits keep linux-allocation-hook.js's semantics: only a positive-size malloc that returns NULL with
 * ENOMEM waits; eight slots bound simultaneous waits; each denial gets a fresh wait number; a grant retries the
 * original malloc once, and a retry that is denied again waits under a new number. A fault (lost connection,
 * malformed frame, exhausted slots or numbers) is sticky and parks every waiting and later denied call; the server
 * ends the attempt.
 *
 * Token mediation keeps linux-cargo-tokens.js's semantics. When CODEX_BUILD_HOOK_FIFO names the jobserver FIFO as
 * major:minor:inode, a one-byte read or write on a descriptor of that FIFO is sent as "transfer" and blocks until its
 * "token"; a read returns the token's byte. The native call then runs with a count of zero and returns 1. A transfer of
 * any other count, or a descriptor whose type or inode cannot be read, reports "failure" and parks without native I/O.
 * Zero-byte calls and other descriptors keep their native behavior.
 *
 * Exec: this library defines execve, execv, execvp, execvpe, execveat, fexecve, execl, execlp, execle, posix_spawn and
 * posix_spawnp, and each passes the next definition an environment that keeps LD_PRELOAD, CODEX_BUILD_HOOK and
 * CODEX_BUILD_HOOK_FIFO as this process loaded them: an entry the caller dropped is appended, and one the caller set is
 * kept. Interposing symbols, rather than patching libc as the malloc hook does, keeps posix_spawn's internal child,
 * which shares this thread's memory, out of the interceptor. The new image loads the library and connects as itself.
 *
 * Static targets: a statically linked image ignores LD_PRELOAD, so its allocations would go unhooked. When the target
 * of execve, execv, execvp, execvpe, execl, execlp, execle, posix_spawn or posix_spawnp is a 64-bit ELF with no
 * PT_INTERP, the call runs the trampoline codex-build-hook-exec, installed beside this library, with arguments
 * path argv... instead. The trampoline loads this library, connects as itself, sends "exec-static" path, waits for
 * "adopt", stops itself with SIGSTOP and, once the server's tracer continues it, execs the static target, so the
 * tracer sees that exec. A target named without a slash is found along PATH as the p-variants search; a target that
 * cannot be read keeps the original call. fexecve and execveat are not redirected.
 *
 * Fork: the child keeps the patched malloc but resets the registry and drops the inherited connection. It connects as
 * a new installation the first time it needs the server, so a child that only execs never connects.
 *
 * Nothing here calls malloc while a hooked call runs: frames are built on the stack and the reader thread is created
 * before the hook is attached, or on a forked child's first denial.
 */
#define _GNU_SOURCE
#include "frida-gum.h"

#include <errno.h>
#include <fcntl.h>
#include <dlfcn.h>
#include <elf.h>
#include <inttypes.h>
#include <limits.h>
#include <pthread.h>
#include <signal.h>
#include <spawn.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/un.h>
#include <unistd.h>

/** Environment variable naming the attempt's socket. */
#define SOCKET_ENV "CODEX_BUILD_HOOK"
/** Exit status when the hook cannot be installed; main never runs. */
#define FAILURE_STATUS 125
/** Supported wire version. */
#define PROTOCOL_VERSION "1"
/** Largest frame payload either side sends; every message is a few short fields. */
#define MAX_FRAME_BYTES 512U
/** Most fields in one message. */
#define MAX_FIELDS 8
/** Simultaneous denied calls the registry can hold. */
#define SLOT_COUNT 8
/** Environment variable naming the jobserver FIFO as major:minor:inode. */
#define FIFO_ENV "CODEX_BUILD_HOOK_FIFO"
/** Simultaneous mediated jobserver transfers. */
#define TRANSFER_COUNT 8

enum phase { FREE, WAITING, PERMITTED, RETRYING };
enum fault { NO_FAULT, SLOT_EXHAUSTED, WAIT_EXHAUSTED, CONNECTION_LOST, MALFORMED_FRAME, NO_INVOCATION_DATA,
    TRANSFER_EXHAUSTED, TRANSFER_REJECTED };

/** One jobserver transfer waiting for its token. */
struct transfer {
    pthread_cond_t condition;
    /** Zero while free. */
    uint64_t sequence;
    bool read;
    bool answered;
    unsigned char byte;
};

/** One denied call waiting for its grant. */
struct slot {
    pthread_cond_t condition;
    uint64_t wait;
    enum phase phase;
};

/** Process-wide hook state. The mutex guards every field except the listener and interceptor. */
static struct {
    pthread_mutex_t mutex;
    /** Signalled only to wake nothing: a faulted call waits on it forever. */
    pthread_cond_t fatal;
    /** Serializes whole frames on the socket. */
    pthread_mutex_t sending;
    /** Connected socket, or -1 before connecting and in a forked child. */
    int socket;
    /** Process that owns the connection; a fork child sees a different pid and reconnects. */
    pid_t owner;
    uint64_t installation;
    uint64_t issued;
    /** Last transfer sequence issued by this installation. */
    uint64_t sequence;
    enum fault fault;
    struct slot slots[SLOT_COUNT];
    struct transfer transfers[TRANSFER_COUNT];
    /** Whether the server sent "adopt" for this process's static exec. */
    bool adopted;
    /** Signalled when adopted is set or a fault occurs. */
    pthread_cond_t adoption;
} hook = { .mutex = PTHREAD_MUTEX_INITIALIZER, .fatal = PTHREAD_COND_INITIALIZER,
    .sending = PTHREAD_MUTEX_INITIALIZER, .socket = -1, .adoption = PTHREAD_COND_INITIALIZER };

/** Arguments that on_enter copies for on_leave. */
struct call { size_t bytes; };
/** Whether on_transfer_enter mediated the call, and the errno to restore. */
struct mediated { bool done; int error; };
/** The jobserver FIFO's identity, when CODEX_BUILD_HOOK_FIFO is set. */
static bool fifo_configured;
static uint32_t fifo_major, fifo_minor;
static uint64_t fifo_inode;

static void *(*original_malloc)(size_t);

/** Writes a diagnostic without allocating. */
static void report(const char *message)
{
    char line[256];
    int length = snprintf(line, sizeof line, "codex-build-hook[%d]: %s\n", (int) getpid(), message);
    if (length > 0) {
        ssize_t ignored = write(STDERR_FILENO, line, (size_t) length < sizeof line ? (size_t) length : sizeof line - 1);
        (void) ignored;
    }
}

/** Ends a process whose hook could not be installed, before main. */
static void fail(const char *message)
{
    report(message);
    _exit(FAILURE_STATUS);
}

/** Records the first fault and parks the calling thread forever. The mutex must be held. */
static void fatal_locked(enum fault fault)
{
    if (hook.fault == NO_FAULT) hook.fault = fault;
    for (;;) pthread_cond_wait(&hook.fatal, &hook.mutex);
}

/** Records a fault and wakes every waiter so that each one parks. The mutex must be held. */
static void fault_locked(enum fault fault)
{
    if (hook.fault == NO_FAULT) hook.fault = fault;
    for (int index = 0; index < SLOT_COUNT; ++index) pthread_cond_signal(&hook.slots[index].condition);
    for (int index = 0; index < TRANSFER_COUNT; ++index) pthread_cond_signal(&hook.transfers[index].condition);
    pthread_cond_broadcast(&hook.adoption);
}

/** Formats an unsigned decimal into the given buffer, which must hold 21 bytes. */
static const char *decimal(uint64_t value, char *buffer)
{
    snprintf(buffer, 21, "%" PRIu64, value);
    return buffer;
}

/** Parses a canonical unsigned decimal; returns false for anything else. */
static bool parse_decimal(const char *text, uint64_t *value)
{
    if (text[0] == '\0' || (text[0] == '0' && text[1] != '\0')) return false;
    uint64_t result = 0;
    for (const char *digit = text; *digit != '\0'; ++digit) {
        if (*digit < '0' || *digit > '9') return false;
        unsigned next = (unsigned) (*digit - '0');
        if (result > (UINT64_MAX - next) / 10) return false;
        result = result * 10 + next;
    }
    *value = result;
    return true;
}

/** Sends one whole frame of fields. Returns zero, or -1 when the connection failed. */
static int send_frame(int socket, const char *const *fields, size_t count)
{
    char frame[4 + MAX_FRAME_BYTES];
    size_t length = 0;
    for (size_t index = 0; index < count; ++index) {
        size_t size = strlen(fields[index]) + 1;
        if (length + size > MAX_FRAME_BYTES) return -1;
        memcpy(frame + 4 + length, fields[index], size);
        length += size;
    }
    frame[0] = (char) (length >> 24);
    frame[1] = (char) (length >> 16);
    frame[2] = (char) (length >> 8);
    frame[3] = (char) length;
    pthread_mutex_lock(&hook.sending);
    size_t sent = 0;
    int outcome = 0;
    while (sent < 4 + length) {
        ssize_t result = send(socket, frame + sent, 4 + length - sent, MSG_NOSIGNAL);
        if (result < 0 && errno == EINTR) continue;
        if (result <= 0) { outcome = -1; break; }
        sent += (size_t) result;
    }
    pthread_mutex_unlock(&hook.sending);
    return outcome;
}

/** Reads exactly length bytes. Returns zero, or -1 at end of file or on error. */
static int receive_exactly(int socket, char *bytes, size_t length)
{
    size_t received = 0;
    while (received < length) {
        ssize_t result = recv(socket, bytes + received, length - received, 0);
        if (result < 0 && errno == EINTR) continue;
        if (result <= 0) return -1;
        received += (size_t) result;
    }
    return 0;
}

/**
 * Reads one frame and splits it into fields that point into payload, which must hold MAX_FRAME_BYTES.
 * Returns the field count, or -1 for a lost connection, or -2 for a malformed frame.
 */
static int receive_frame(int socket, char *payload, const char **fields)
{
    unsigned char header[4];
    if (receive_exactly(socket, (char *) header, 4) < 0) return -1;
    uint32_t length = (uint32_t) header[0] << 24 | (uint32_t) header[1] << 16 | (uint32_t) header[2] << 8 | header[3];
    if (length == 0 || length > MAX_FRAME_BYTES) return -2;
    if (receive_exactly(socket, payload, length) < 0) return -1;
    if (payload[length - 1] != '\0') return -2;
    int count = 0;
    for (uint32_t start = 0; start < length;) {
        if (count == MAX_FIELDS) return -2;
        fields[count++] = payload + start;
        start += (uint32_t) strlen(payload + start) + 1;
    }
    return count;
}

/** Reads field 22 of /proc/self/stat, the process start time in clock ticks. Returns false if unreadable. */
static bool start_time(char *buffer)
{
    char stat[1024];
    int descriptor = open("/proc/self/stat", O_RDONLY | O_CLOEXEC);
    if (descriptor < 0) return false;
    ssize_t length = read(descriptor, stat, sizeof stat - 1);
    close(descriptor);
    if (length <= 0) return false;
    stat[length] = '\0';
    /* The command name is parenthesized and may itself contain parentheses and spaces. */
    char *field = strrchr(stat, ')');
    if (field == NULL) return false;
    /* After ") " comes field 3; field 22 is 19 fields later. */
    field += 2;
    for (int index = 3; index < 22; ++index) {
        field = strchr(field, ' ');
        if (field == NULL) return false;
        ++field;
    }
    size_t digits = strspn(field, "0123456789");
    if (digits == 0 || digits > 20) return false;
    memcpy(buffer, field, digits);
    buffer[digits] = '\0';
    return true;
}

/** Applies one grant from the server and answers it. Returns false for a malformed grant. */
static bool apply_grant(int socket, const char *text)
{
    uint64_t wait;
    if (!parse_decimal(text, &wait) || wait == 0) return false;
    const char *status = "unknown";
    pthread_mutex_lock(&hook.mutex);
    for (int index = 0; index < SLOT_COUNT; ++index) {
        struct slot *slot = &hook.slots[index];
        if (slot->wait != wait) continue;
        if (slot->phase == WAITING) {
            slot->phase = PERMITTED;
            pthread_cond_signal(&slot->condition);
            status = "accepted";
        }
        else status = "duplicate";
        break;
    }
    /* An issued wait that is no longer held was already granted and retried. */
    if (strcmp(status, "unknown") == 0 && wait <= hook.issued) status = "duplicate";
    pthread_mutex_unlock(&hook.mutex);
    const char *fields[] = { "granted", text, status };
    return send_frame(socket, fields, 3) == 0;
}

/**
 * Delivers one token to its waiting transfer. A read needs a byte and a write must not carry one.
 * Returns false for an unknown sequence or a token of the wrong shape.
 */
static bool apply_token(const char *sequence_text, const char *byte_text)
{
    uint64_t sequence, byte = 0;
    if (!parse_decimal(sequence_text, &sequence) || sequence == 0) return false;
    if (byte_text != NULL && (!parse_decimal(byte_text, &byte) || byte > 255)) return false;
    bool applied = false;
    pthread_mutex_lock(&hook.mutex);
    for (int index = 0; index < TRANSFER_COUNT; ++index) {
        struct transfer *transfer = &hook.transfers[index];
        if (transfer->sequence != sequence || transfer->answered) continue;
        if (transfer->read != (byte_text != NULL)) break;
        transfer->byte = (unsigned char) byte;
        transfer->answered = true;
        pthread_cond_signal(&transfer->condition);
        applied = true;
        break;
    }
    pthread_mutex_unlock(&hook.mutex);
    return applied;
}

/** Serves one connection until it fails, then faults the registry so that every waiter parks. */
static void *read_server(void *argument)
{
    int socket = (int) (intptr_t) argument;
    char payload[MAX_FRAME_BYTES];
    const char *fields[MAX_FIELDS];
    enum fault fault;
    for (;;) {
        int count = receive_frame(socket, payload, fields);
        if (count == -1) { fault = CONNECTION_LOST; break; }
        if (count < 0) { fault = MALFORMED_FRAME; break; }
        if (count == 2 && strcmp(fields[0], "grant") == 0) {
            if (!apply_grant(socket, fields[1])) { fault = MALFORMED_FRAME; break; }
        }
        else if ((count == 2 || count == 3) && strcmp(fields[0], "token") == 0) {
            if (!apply_token(fields[1], count == 3 ? fields[2] : NULL)) { fault = TRANSFER_REJECTED; break; }
        }
        else if (count == 1 && strcmp(fields[0], "adopt") == 0) {
            pthread_mutex_lock(&hook.mutex);
            hook.adopted = true;
            pthread_cond_broadcast(&hook.adoption);
            pthread_mutex_unlock(&hook.mutex);
        }
        else { fault = MALFORMED_FRAME; break; }
    }
    pthread_mutex_lock(&hook.mutex);
    fault_locked(fault);
    pthread_mutex_unlock(&hook.mutex);
    return NULL;
}

/**
 * Connects, identifies this process and starts the reader. The mutex must not be held.
 * Returns NULL, or a description of the failure.
 */
static const char *connect_server(void)
{
    const char *path = getenv(SOCKET_ENV);
    if (path == NULL) return SOCKET_ENV " is not set";
    struct sockaddr_un address = { .sun_family = AF_UNIX };
    if (strlen(path) >= sizeof address.sun_path) return SOCKET_ENV " is too long";
    strcpy(address.sun_path, path);
    int socket_descriptor = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (socket_descriptor < 0) return "cannot create the hook socket";
    if (connect(socket_descriptor, (struct sockaddr *) &address, sizeof address) < 0) {
        close(socket_descriptor);
        return "cannot connect to the hook server";
    }
    char pid[21], start[21];
    if (!start_time(start)) {
        close(socket_descriptor);
        return "cannot read the process start time";
    }
    const char *hello[] = { "hello", PROTOCOL_VERSION, decimal((uint64_t) getpid(), pid), start };
    if (send_frame(socket_descriptor, hello, 4) < 0) {
        close(socket_descriptor);
        return "cannot send hello";
    }
    char payload[MAX_FRAME_BYTES];
    const char *fields[MAX_FIELDS];
    uint64_t installation;
    int count = receive_frame(socket_descriptor, payload, fields);
    if (count != 2 || strcmp(fields[0], "welcome") != 0 || !parse_decimal(fields[1], &installation) ||
        installation == 0) {
        close(socket_descriptor);
        return "the hook server did not welcome this process";
    }
    pthread_t reader;
    pthread_attr_t attributes;
    pthread_attr_init(&attributes);
    pthread_attr_setdetachstate(&attributes, PTHREAD_CREATE_DETACHED);
    int created = pthread_create(&reader, &attributes, read_server, (void *) (intptr_t) socket_descriptor);
    pthread_attr_destroy(&attributes);
    if (created != 0) {
        close(socket_descriptor);
        return "cannot start the hook reader";
    }
    pthread_mutex_lock(&hook.mutex);
    hook.socket = socket_descriptor;
    hook.owner = getpid();
    hook.installation = installation;
    pthread_mutex_unlock(&hook.mutex);
    return NULL;
}

/** Connects a forked child as its own installation the first time it needs the server; parks on failure. */
static void ensure_connected(void)
{
    pthread_mutex_lock(&hook.mutex);
    bool connected = hook.owner == getpid();
    pthread_mutex_unlock(&hook.mutex);
    if (connected) return;
    const char *problem = connect_server();
    if (problem == NULL) return;
    report(problem);
    pthread_mutex_lock(&hook.mutex);
    fatal_locked(CONNECTION_LOST);
}

/** Copies the requested size before malloc runs. */
static void on_enter(GumInvocationContext *context, gpointer user_data)
{
    (void) user_data;
    struct call *call = GUM_IC_GET_INVOCATION_DATA(context, struct call);
    if (call == NULL) {
        pthread_mutex_lock(&hook.mutex);
        fatal_locked(NO_INVOCATION_DATA);
    }
    call->bytes = (size_t) gum_invocation_context_get_nth_argument(context, 0);
}

/**
 * Holds a denied call until the server grants it, then retries the original malloc.
 * Successful and ineligible calls keep their result and errno.
 */
static void on_leave(GumInvocationContext *context, gpointer user_data)
{
    (void) user_data;
    struct call *call = GUM_IC_GET_INVOCATION_DATA(context, struct call);
    void *result = gum_invocation_context_get_return_value(context);
    int error = context->system_error;
    if (call == NULL) {
        pthread_mutex_lock(&hook.mutex);
        fatal_locked(NO_INVOCATION_DATA);
    }
    if (result != NULL || call->bytes == 0 || error != ENOMEM) return;

    ensure_connected();

    pthread_mutex_lock(&hook.mutex);
    if (hook.fault != NO_FAULT) fatal_locked(hook.fault);
    struct slot *slot = NULL;
    for (int index = 0; index < SLOT_COUNT && slot == NULL; ++index)
        if (hook.slots[index].phase == FREE) slot = &hook.slots[index];
    if (slot == NULL) fatal_locked(SLOT_EXHAUSTED);
    uint64_t thread = (uint64_t) syscall(SYS_gettid);
    for (;;) {
        if (hook.issued == UINT64_MAX / 2) fatal_locked(WAIT_EXHAUSTED);
        slot->wait = ++hook.issued;
        slot->phase = WAITING;
        int socket = hook.socket;
        char wait_text[21], thread_text[21], bytes_text[21], error_text[21];
        const char *blocked[] = { "blocked", decimal(slot->wait, wait_text), decimal(thread, thread_text),
            decimal(call->bytes, bytes_text), decimal((uint64_t) error, error_text) };
        /* Sending outside the mutex lets the reader apply a grant that arrives before the send returns. */
        pthread_mutex_unlock(&hook.mutex);
        int sent = send_frame(socket, blocked, 5);
        pthread_mutex_lock(&hook.mutex);
        if (sent < 0) fatal_locked(CONNECTION_LOST);
        while (slot->phase == WAITING && hook.fault == NO_FAULT) pthread_cond_wait(&slot->condition, &hook.mutex);
        if (hook.fault != NO_FAULT) fatal_locked(hook.fault);
        slot->phase = RETRYING;
        pthread_mutex_unlock(&hook.mutex);

        errno = error;
        result = original_malloc(call->bytes);
        error = errno;

        pthread_mutex_lock(&hook.mutex);
        if (hook.fault != NO_FAULT) fatal_locked(hook.fault);
        if (result != NULL || error != ENOMEM) {
            slot->phase = FREE;
            slot->wait = 0;
            pthread_mutex_unlock(&hook.mutex);
            gum_invocation_context_replace_return_value(context, result);
            context->system_error = error;
            return;
        }
    }
}

/** Reports a jobserver failure and parks the calling thread without native I/O. The mutex must not be held. */
static void park_transfer(uint64_t sequence, const char *cause)
{
    ensure_connected();
    pthread_mutex_lock(&hook.mutex);
    int socket = hook.socket;
    pthread_mutex_unlock(&hook.mutex);
    char sequence_text[21];
    const char *failure[] = { "failure", decimal(sequence, sequence_text), cause };
    send_frame(socket, failure, 3);
    pthread_mutex_lock(&hook.mutex);
    fatal_locked(TRANSFER_REJECTED);
}

/**
 * Classifies a descriptor: 1 for the jobserver FIFO, 0 for another file, or -1 when its type or inode is unreadable.
 */
static int classify_descriptor(int descriptor)
{
    struct statx metadata;
    if (statx(descriptor, "", AT_EMPTY_PATH, STATX_TYPE | STATX_INO, &metadata) != 0) return errno == EBADF ? 0 : -1;
    if ((metadata.stx_mask & STATX_TYPE) == 0) return -1;
    if (!S_ISFIFO(metadata.stx_mode)) return 0;
    if ((metadata.stx_mask & STATX_INO) == 0) return -1;
    return metadata.stx_dev_major == fifo_major && metadata.stx_dev_minor == fifo_minor &&
        metadata.stx_ino == fifo_inode ? 1 : 0;
}

/** Mediates a one-byte read or write of the jobserver FIFO; the listener's user data is non-NULL for read. */
static void on_transfer_enter(GumInvocationContext *context, gpointer user_data)
{
    struct mediated *mediated = GUM_IC_GET_INVOCATION_DATA(context, struct mediated);
    if (mediated == NULL) {
        pthread_mutex_lock(&hook.mutex);
        fatal_locked(NO_INVOCATION_DATA);
    }
    mediated->done = false;
    int error = context->system_error;
    int descriptor = (int) (intptr_t) gum_invocation_context_get_nth_argument(context, 0);
    unsigned char *buffer = gum_invocation_context_get_nth_argument(context, 1);
    size_t count = (size_t) gum_invocation_context_get_nth_argument(context, 2);
    if (count == 0) return;
    int kind = classify_descriptor(descriptor);
    context->system_error = error;
    if (kind == 0) return;

    ensure_connected();
    pthread_mutex_lock(&hook.mutex);
    if (hook.fault != NO_FAULT) fatal_locked(hook.fault);
    if (hook.sequence == UINT64_MAX / 2) fatal_locked(TRANSFER_EXHAUSTED);
    uint64_t sequence = ++hook.sequence;
    pthread_mutex_unlock(&hook.mutex);
    if (kind < 0) park_transfer(sequence, "Descriptor type or inode is unavailable");
    if (count != 1) park_transfer(sequence, "Jobserver transfer count differs from one");

    bool read = user_data != NULL;
    pthread_mutex_lock(&hook.mutex);
    struct transfer *transfer = NULL;
    for (int index = 0; index < TRANSFER_COUNT && transfer == NULL; ++index)
        if (hook.transfers[index].sequence == 0) transfer = &hook.transfers[index];
    if (transfer == NULL) fatal_locked(TRANSFER_EXHAUSTED);
    transfer->sequence = sequence;
    transfer->read = read;
    transfer->answered = false;
    int socket = hook.socket;
    pthread_mutex_unlock(&hook.mutex);

    char sequence_text[21], byte_text[21];
    const char *request[] = { "transfer", decimal(sequence, sequence_text), read ? "read" : "write",
        read ? NULL : decimal(buffer[0], byte_text) };
    int sent = send_frame(socket, request, read ? 3 : 4);
    pthread_mutex_lock(&hook.mutex);
    if (sent < 0) fatal_locked(CONNECTION_LOST);
    while (!transfer->answered && hook.fault == NO_FAULT) pthread_cond_wait(&transfer->condition, &hook.mutex);
    if (hook.fault != NO_FAULT) fatal_locked(hook.fault);
    if (read) buffer[0] = transfer->byte;
    transfer->sequence = 0;
    pthread_mutex_unlock(&hook.mutex);

    /* The native call still runs, transferring nothing. */
    gum_invocation_context_replace_nth_argument(context, 2, GSIZE_TO_POINTER(0));
    mediated->done = true;
    mediated->error = error;
    context->system_error = error;
}

/** Reports the mediated byte as transferred. */
static void on_transfer_leave(GumInvocationContext *context, gpointer user_data)
{
    (void) user_data;
    struct mediated *mediated = GUM_IC_GET_INVOCATION_DATA(context, struct mediated);
    if (mediated == NULL || !mediated->done) return;
    gum_invocation_context_replace_return_value(context, GSIZE_TO_POINTER(1));
    context->system_error = mediated->error;
}

/**
 * Parses CODEX_BUILD_HOOK_FIFO. Returns NULL, or a description of the failure.
 */
static const char *configure_fifo(void)
{
    const char *value = getenv(FIFO_ENV);
    if (value == NULL) return NULL;
    char copy[64];
    if (strlen(value) >= sizeof copy) return FIFO_ENV " is too long";
    strcpy(copy, value);
    char *minor = strchr(copy, ':');
    char *inode = minor == NULL ? NULL : strchr(minor + 1, ':');
    if (inode == NULL) return FIFO_ENV " is not major:minor:inode";
    *minor++ = '\0';
    *inode++ = '\0';
    uint64_t major_value, minor_value;
    if (!parse_decimal(copy, &major_value) || !parse_decimal(minor, &minor_value) ||
        !parse_decimal(inode, &fifo_inode) || major_value > UINT32_MAX || minor_value > UINT32_MAX)
        return FIFO_ENV " is not major:minor:inode";
    fifo_major = (uint32_t) major_value;
    fifo_minor = (uint32_t) minor_value;
    fifo_configured = true;
    return NULL;
}

/** Variables that exec must carry into the next image, so that it loads the library and reaches the same server. */
static const char *const RESTORED_NAMES[] = { "LD_PRELOAD", SOCKET_ENV, FIFO_ENV };
#define RESTORED_LIMIT (sizeof RESTORED_NAMES / sizeof RESTORED_NAMES[0])
/** The longest restored entry, NAME=value, including its NUL. */
#define MAX_ENTRY_BYTES 4096

/** This process's restored entries as NAME=value, captured at load; only names that were set are kept. */
static char restored[RESTORED_LIMIT][MAX_ENTRY_BYTES];
/** The length of each restored entry's name. */
static size_t restored_name_length[RESTORED_LIMIT];
static size_t restored_count;

typedef int (*execve_function)(const char *, char *const [], char *const []);
typedef int (*fexecve_function)(int, char *const [], char *const []);
typedef int (*execveat_function)(int, const char *, char *const [], char *const [], int);
typedef int (*spawn_function)(pid_t *, const char *, const posix_spawn_file_actions_t *, const posix_spawnattr_t *,
    char *const [], char *const []);

/**
 * Captures the entries that exec restores. Returns NULL, or a description of the failure.
 */
static const char *capture_environment(void)
{
    for (size_t index = 0; index < RESTORED_LIMIT; ++index) {
        const char *value = getenv(RESTORED_NAMES[index]);
        if (value == NULL) continue;
        int length = snprintf(restored[restored_count], MAX_ENTRY_BYTES, "%s=%s", RESTORED_NAMES[index], value);
        if (length < 0 || length >= MAX_ENTRY_BYTES) return "a hook environment variable is too long";
        restored_name_length[restored_count] = strlen(RESTORED_NAMES[index]);
        ++restored_count;
    }
    return NULL;
}

/**
 * The definitions this library interposes, resolved at load so that a forked child never calls dlsym. A call that
 * arrives before the constructor resolves its definition on first use.
 */
static execve_function next_execve, next_execvpe;
static fexecve_function next_fexecve;
static spawn_function next_posix_spawn, next_posix_spawnp;

/** Resolves the interposed definitions. Returns false if one is missing. */
static bool resolve_exec_functions(void)
{
    next_execve = (execve_function) dlsym(RTLD_NEXT, "execve");
    next_execvpe = (execve_function) dlsym(RTLD_NEXT, "execvpe");
    next_fexecve = (fexecve_function) dlsym(RTLD_NEXT, "fexecve");
    next_posix_spawn = (spawn_function) dlsym(RTLD_NEXT, "posix_spawn");
    next_posix_spawnp = (spawn_function) dlsym(RTLD_NEXT, "posix_spawnp");
    return next_execve != NULL && next_execvpe != NULL && next_fexecve != NULL && next_posix_spawn != NULL &&
        next_posix_spawnp != NULL;
}

/**
 * Returns environment, or a mapped copy that appends every captured entry whose name it lacks. *mapping and *size
 * describe the copy for munmap, and *mapping is NULL when no copy was needed. Returns NULL if mapping fails. The
 * function only maps memory, so a child between fork and exec may call it.
 */
static char *const *restore_environment(char *const environment[], void **mapping, size_t *size)
{
    *mapping = NULL;
    size_t count = 0;
    if (environment != NULL)
        while (environment[count] != NULL) ++count;
    bool missing[RESTORED_LIMIT];
    size_t missing_count = 0;
    for (size_t entry = 0; entry < restored_count; ++entry) {
        missing[entry] = true;
        for (size_t index = 0; index < count && missing[entry]; ++index)
            if (strncmp(environment[index], restored[entry], restored_name_length[entry] + 1) == 0) missing[entry] = false;
        if (missing[entry]) ++missing_count;
    }
    if (missing_count == 0) return environment;
    *size = (count + missing_count + 1) * sizeof(char *);
    char **copy = mmap(NULL, *size, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (copy == MAP_FAILED) return NULL;
    size_t next = 0;
    for (; next < count; ++next) copy[next] = environment[next];
    for (size_t entry = 0; entry < restored_count; ++entry)
        if (missing[entry]) copy[next++] = restored[entry];
    copy[next] = NULL;
    *mapping = copy;
    return copy;
}

/** Releases a copy from restore_environment, keeping errno. */
static void release_environment(void *mapping, size_t size)
{
    if (mapping == NULL) return;
    int error = errno;
    munmap(mapping, size);
    errno = error;
}

/** The trampoline beside this library, or an empty string if its path could not be formed. */
static char trampoline[PATH_MAX];

/** Locates the trampoline beside this library. Returns NULL, or a description of the failure. */
static const char *locate_trampoline(void)
{
    Dl_info library;
    if (dladdr((void *) locate_trampoline, &library) == 0 || library.dli_fname == NULL)
        return "cannot locate the hook library";
    const char *slash = strrchr(library.dli_fname, '/');
    size_t directory = slash == NULL ? 0 : (size_t) (slash - library.dli_fname + 1);
    int length = snprintf(trampoline, sizeof trampoline, "%.*scodex-build-hook-exec", (int) directory,
        library.dli_fname);
    if (length < 0 || (size_t) length >= sizeof trampoline) return "the hook library path is too long";
    return NULL;
}

/**
 * Reports whether path names a 64-bit ELF image with no program interpreter. Uses only system calls, so a child
 * between fork and exec may call it, and keeps errno.
 */
static bool is_static_image(const char *path)
{
    int error = errno;
    bool result = false;
    int descriptor = open(path, O_RDONLY | O_CLOEXEC);
    if (descriptor >= 0) {
        Elf64_Ehdr header;
        if (pread(descriptor, &header, sizeof header, 0) == (ssize_t) sizeof header &&
            memcmp(header.e_ident, ELFMAG, SELFMAG) == 0 && header.e_ident[EI_CLASS] == ELFCLASS64 &&
            header.e_phentsize == sizeof(Elf64_Phdr) && (header.e_type == ET_EXEC || header.e_type == ET_DYN)) {
            result = true;
            for (Elf64_Half index = 0; index < header.e_phnum && result; ++index) {
                Elf64_Phdr segment;
                if (pread(descriptor, &segment, sizeof segment,
                    (off_t) (header.e_phoff + (Elf64_Off) index * sizeof segment)) != (ssize_t) sizeof segment)
                    result = false;
                else if (segment.p_type == PT_INTERP)
                    result = false;
            }
        }
        close(descriptor);
    }
    errno = error;
    return result;
}

/**
 * Finds file along PATH as execvp does, writing the first executable candidate to buffer. Returns false if file has a
 * slash, nothing is found, or a candidate does not fit.
 */
static bool search_path(const char *file, char buffer[PATH_MAX])
{
    if (strchr(file, '/') != NULL || file[0] == '\0') return false;
    int error = errno;
    const char *search = getenv("PATH");
    if (search == NULL) search = "/bin:/usr/bin";
    bool found = false;
    while (!found) {
        const char *end = strchrnul(search, ':');
        size_t length = (size_t) (end - search);
        int written = length == 0 ? snprintf(buffer, PATH_MAX, "%s", file) :
            snprintf(buffer, PATH_MAX, "%.*s/%s", (int) length, search, file);
        if (written > 0 && written < PATH_MAX && access(buffer, X_OK) == 0) found = true;
        if (*end == '\0') break;
        search = end + 1;
    }
    errno = error;
    return found;
}

/** Counts the entries of a NULL-terminated vector. */
static size_t vector_length(char *const vector[])
{
    size_t count = 0;
    if (vector != NULL)
        while (vector[count] != NULL) ++count;
    return count;
}

/**
 * Fills redirected, of vector_length(arguments) + 3 entries, with the trampoline's arguments: trampoline, path, then
 * every original argument.
 */
static void trampoline_arguments(const char *path, char *const arguments[], size_t count, char **redirected)
{
    redirected[0] = trampoline;
    redirected[1] = (char *) path;
    for (size_t index = 0; index < count; ++index) redirected[index + 2] = arguments[index];
    redirected[count + 2] = NULL;
}

__attribute__((visibility("default"))) int execve(const char *path, char *const arguments[],
    char *const environment[])
{
    if (next_execve == NULL) next_execve = (execve_function) dlsym(RTLD_NEXT, "execve");
    void *mapping;
    size_t size;
    char *const *restored_environment = restore_environment(environment, &mapping, &size);
    if (restored_environment == NULL) { errno = ENOMEM; return -1; }
    int result;
    if (trampoline[0] != '\0' && is_static_image(path)) {
        size_t count = vector_length(arguments);
        char *redirected[count + 3];
        trampoline_arguments(path, arguments, count, redirected);
        result = next_execve(trampoline, redirected, restored_environment);
    }
    else
        result = next_execve(path, arguments, restored_environment);
    release_environment(mapping, size);
    return result;
}

__attribute__((visibility("default"))) int execvpe(const char *file, char *const arguments[],
    char *const environment[])
{
    char found[PATH_MAX];
    const char *path = file;
    if (strchr(file, '/') == NULL) path = search_path(file, found) ? found : NULL;
    if (path != NULL && trampoline[0] != '\0' && is_static_image(path)) return execve(path, arguments, environment);
    if (next_execvpe == NULL) next_execvpe = (execve_function) dlsym(RTLD_NEXT, "execvpe");
    void *mapping;
    size_t size;
    char *const *restored_environment = restore_environment(environment, &mapping, &size);
    if (restored_environment == NULL) { errno = ENOMEM; return -1; }
    int result = next_execvpe(file, arguments, restored_environment);
    release_environment(mapping, size);
    return result;
}

__attribute__((visibility("default"))) int fexecve(int descriptor, char *const arguments[],
    char *const environment[])
{
    if (next_fexecve == NULL) next_fexecve = (fexecve_function) dlsym(RTLD_NEXT, "fexecve");
    void *mapping;
    size_t size;
    char *const *restored_environment = restore_environment(environment, &mapping, &size);
    if (restored_environment == NULL) { errno = ENOMEM; return -1; }
    int result = next_fexecve(descriptor, arguments, restored_environment);
    release_environment(mapping, size);
    return result;
}

__attribute__((visibility("default"))) int execveat(int directory, const char *path, char *const arguments[],
    char *const environment[], int flags)
{
    void *mapping;
    size_t size;
    char *const *restored_environment = restore_environment(environment, &mapping, &size);
    if (restored_environment == NULL) { errno = ENOMEM; return -1; }
    /* glibc before 2.34 has no execveat wrapper, so the system call is made directly. */
    int result = (int) syscall(SYS_execveat, directory, path, arguments, restored_environment, flags);
    release_environment(mapping, size);
    return result;
}

__attribute__((visibility("default"))) int execv(const char *path, char *const arguments[])
{
    return execve(path, arguments, environ);
}

__attribute__((visibility("default"))) int execvp(const char *file, char *const arguments[])
{
    return execvpe(file, arguments, environ);
}

/** Counts the arguments of an execl-family call, including the first and excluding the terminating NULL. */
#define COUNT_ARGUMENTS(list, count) \
    do { \
        va_list counting; \
        va_copy(counting, list); \
        count = 1; \
        while (va_arg(counting, const char *) != NULL) ++count; \
        va_end(counting); \
    } while (0)

/** Fills an argument vector of count + 1 entries from an execl-family call. */
static void collect_arguments(const char *first, va_list list, size_t count, char **vector)
{
    vector[0] = (char *) first;
    for (size_t index = 1; index < count; ++index) vector[index] = va_arg(list, char *);
    vector[count] = NULL;
}

__attribute__((visibility("default"))) int execl(const char *path, const char *first, ...)
{
    va_list list;
    va_start(list, first);
    size_t count;
    COUNT_ARGUMENTS(list, count);
    char *vector[count + 1];
    collect_arguments(first, list, count, vector);
    va_end(list);
    return execve(path, vector, environ);
}

__attribute__((visibility("default"))) int execlp(const char *file, const char *first, ...)
{
    va_list list;
    va_start(list, first);
    size_t count;
    COUNT_ARGUMENTS(list, count);
    char *vector[count + 1];
    collect_arguments(first, list, count, vector);
    va_end(list);
    return execvpe(file, vector, environ);
}

__attribute__((visibility("default"))) int execle(const char *path, const char *first, ...)
{
    va_list list;
    va_start(list, first);
    size_t count;
    COUNT_ARGUMENTS(list, count);
    char *vector[count + 1];
    collect_arguments(first, list, count, vector);
    (void) va_arg(list, char *);
    char *const *environment = va_arg(list, char *const *);
    va_end(list);
    return execve(path, vector, environment);
}

/** Starts a child through the next posix_spawn or posix_spawnp with the restored environment. */
static int spawn(const char *name, spawn_function *next, pid_t *child, const char *path,
    const posix_spawn_file_actions_t *actions, const posix_spawnattr_t *attributes, char *const arguments[],
    char *const environment[])
{
    char found[PATH_MAX];
    const char *image = path;
    if (strcmp(name, "posix_spawnp") == 0 && strchr(path, '/') == NULL) image = search_path(path, found) ? found : NULL;
    if (image != NULL && trampoline[0] != '\0' && is_static_image(image)) {
        size_t count = vector_length(arguments);
        char *redirected[count + 3];
        trampoline_arguments(image, arguments, count, redirected);
        return spawn("posix_spawn", &next_posix_spawn, child, trampoline, actions, attributes, redirected,
            environment);
    }
    if (*next == NULL) *next = (spawn_function) dlsym(RTLD_NEXT, name);
    void *mapping;
    size_t size;
    char *const *restored_environment = restore_environment(environment, &mapping, &size);
    if (restored_environment == NULL) return ENOMEM;
    int result = (*next)(child, path, actions, attributes, arguments, restored_environment);
    release_environment(mapping, size);
    return result;
}

__attribute__((visibility("default"))) int posix_spawn(pid_t *child, const char *path,
    const posix_spawn_file_actions_t *actions, const posix_spawnattr_t *attributes, char *const arguments[],
    char *const environment[])
{
    return spawn("posix_spawn", &next_posix_spawn, child, path, actions, attributes, arguments, environment);
}

__attribute__((visibility("default"))) int posix_spawnp(pid_t *child, const char *file,
    const posix_spawn_file_actions_t *actions, const posix_spawnattr_t *attributes, char *const arguments[],
    char *const environment[])
{
    return spawn("posix_spawnp", &next_posix_spawnp, child, file, actions, attributes, arguments, environment);
}

/**
 * The trampoline's entry: announces the static image at path, waits for the server to adopt it, stops so that the
 * server's tracer can take over, then execs it. Returns only on failure, with FAILURE_STATUS.
 */
__attribute__((visibility("default"))) int codex_build_hook_exec_static(const char *path, char *const arguments[])
{
    ensure_connected();
    pthread_mutex_lock(&hook.mutex);
    int socket = hook.socket;
    pthread_mutex_unlock(&hook.mutex);
    const char *announcement[] = { "exec-static", path };
    if (send_frame(socket, announcement, 2) < 0) {
        report("cannot announce the static image");
        return FAILURE_STATUS;
    }
    pthread_mutex_lock(&hook.mutex);
    while (!hook.adopted && hook.fault == NO_FAULT) pthread_cond_wait(&hook.adoption, &hook.mutex);
    bool adopted = hook.adopted;
    pthread_mutex_unlock(&hook.mutex);
    if (!adopted) {
        report("the hook server did not adopt the static image");
        return FAILURE_STATUS;
    }
    raise(SIGSTOP);
    next_execve(path, arguments, environ);
    report("cannot exec the static image");
    return FAILURE_STATUS;
}

/** Attaches one listener to a global export. Returns false on failure. */
static bool attach(GumInterceptor *interceptor, const char *name, GumInvocationListener *listener)
{
    gpointer target = GSIZE_TO_POINTER(gum_module_find_global_export_by_name(name));
    return target != NULL && gum_interceptor_attach(interceptor, target, listener, NULL) == GUM_ATTACH_OK;
}

/** Gives a forked child a fresh registry; it reconnects on its first denial. */
static void reset_child(void)
{
    pthread_mutex_unlock(&hook.sending);
    pthread_mutex_unlock(&hook.mutex);
    pthread_cond_init(&hook.fatal, NULL);
    if (hook.socket >= 0) close(hook.socket);
    hook.socket = -1;
    hook.owner = 0;
    hook.installation = 0;
    hook.issued = 0;
    hook.sequence = 0;
    hook.fault = NO_FAULT;
    hook.adopted = false;
    pthread_cond_init(&hook.adoption, NULL);
    for (int index = 0; index < TRANSFER_COUNT; ++index) {
        pthread_cond_init(&hook.transfers[index].condition, NULL);
        hook.transfers[index].sequence = 0;
    }
    for (int index = 0; index < SLOT_COUNT; ++index) {
        pthread_cond_init(&hook.slots[index].condition, NULL);
        hook.slots[index].wait = 0;
        hook.slots[index].phase = FREE;
    }
}

/** Holds the registry across fork so that the child never copies it mid-update. */
static void lock_for_fork(void)
{
    pthread_mutex_lock(&hook.mutex);
    pthread_mutex_lock(&hook.sending);
}

static void unlock_after_fork(void)
{
    pthread_mutex_unlock(&hook.sending);
    pthread_mutex_unlock(&hook.mutex);
}

__attribute__((constructor)) static void install(void)
{
    for (int index = 0; index < SLOT_COUNT; ++index) pthread_cond_init(&hook.slots[index].condition, NULL);
    for (int index = 0; index < TRANSFER_COUNT; ++index) pthread_cond_init(&hook.transfers[index].condition, NULL);
    const char *problem = configure_fifo();
    if (problem != NULL) fail(problem);
    problem = capture_environment();
    if (problem != NULL) fail(problem);
    if (!resolve_exec_functions()) fail("an exec function is not exported");
    problem = locate_trampoline();
    if (problem != NULL) fail(problem);
    problem = connect_server();
    if (problem != NULL) fail(problem);
    if (pthread_atfork(lock_for_fork, unlock_after_fork, reset_child) != 0) fail("cannot register the fork handlers");

    gum_init_embedded();
    /* Follow the process's global malloc binding, including an executable's own allocator, as rustc's jemalloc. */
    original_malloc = GSIZE_TO_POINTER(gum_module_find_global_export_by_name("malloc"));
    if (original_malloc == NULL) fail("malloc is not exported");
    GumInterceptor *interceptor = gum_interceptor_obtain();
    GumInvocationListener *listener = gum_make_call_listener(on_enter, on_leave, NULL, NULL);
    gum_interceptor_begin_transaction(interceptor);
    bool attached = gum_interceptor_attach(interceptor, GSIZE_TO_POINTER(original_malloc), listener, NULL) ==
        GUM_ATTACH_OK;
    if (attached && fifo_configured) {
        static int read_marker;
        GumInvocationListener *reads = gum_make_call_listener(on_transfer_enter, on_transfer_leave, &read_marker, NULL);
        GumInvocationListener *writes = gum_make_call_listener(on_transfer_enter, on_transfer_leave, NULL, NULL);
        attached = attach(interceptor, "read", reads) && attach(interceptor, "write", writes);
    }
    gum_interceptor_end_transaction(interceptor);
    if (!attached) fail("cannot attach the hooks");
}
