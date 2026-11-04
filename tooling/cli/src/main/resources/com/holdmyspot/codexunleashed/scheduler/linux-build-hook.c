/*
 * Preloaded allocation hook for one Linux compiler attempt, replacing per-process Frida injection.
 *
 * Loading: LD_PRELOAD names this library and CODEX_BUILD_HOOK names the attempt's Unix socket. The constructor
 * attaches the malloc hook, connects, sends hello and waits for welcome before returning, so main never runs
 * unhooked. Allocations made by ld.so, and by constructors that run before this one, are not covered.
 *
 * Wire: 4-byte big-endian payload length, then NUL-terminated fields; protocol version 1.
 * - Client: "hello" version pid start; "blocked" wait thread bytes errno; "granted" wait unknown|accepted|duplicate.
 * - Server: "welcome" installation; "grant" wait.
 * The start is field 22 of /proc/<pid>/stat. Numbers are canonical unsigned decimals.
 *
 * Allocation waits keep linux-allocation-hook.js's semantics: only a positive-size malloc that returns NULL with
 * ENOMEM waits; eight slots bound simultaneous waits; each denial gets a fresh wait number; a grant retries the
 * original malloc once, and a retry that is denied again waits under a new number. A fault (lost connection,
 * malformed frame, exhausted slots or numbers) is sticky and parks every waiting and later denied call; the server
 * ends the attempt.
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
#include <inttypes.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
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

enum phase { FREE, WAITING, PERMITTED, RETRYING };
enum fault { NO_FAULT, SLOT_EXHAUSTED, WAIT_EXHAUSTED, CONNECTION_LOST, MALFORMED_FRAME, NO_INVOCATION_DATA };

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
    enum fault fault;
    struct slot slots[SLOT_COUNT];
} hook = { .mutex = PTHREAD_MUTEX_INITIALIZER, .fatal = PTHREAD_COND_INITIALIZER,
    .sending = PTHREAD_MUTEX_INITIALIZER, .socket = -1 };

/** Arguments that on_enter copies for on_leave. */
struct call { size_t bytes; };

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
        if (count < 0 || count != 2 || strcmp(fields[0], "grant") != 0) { fault = MALFORMED_FRAME; break; }
        if (!apply_grant(socket, fields[1])) { fault = MALFORMED_FRAME; break; }
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

    /* A forked child connects as its own installation on its first denial. */
    pthread_mutex_lock(&hook.mutex);
    bool connected = hook.owner == getpid();
    pthread_mutex_unlock(&hook.mutex);
    if (!connected) {
        const char *problem = connect_server();
        if (problem != NULL) {
            report(problem);
            pthread_mutex_lock(&hook.mutex);
            fatal_locked(CONNECTION_LOST);
        }
    }

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
    hook.fault = NO_FAULT;
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
    const char *problem = connect_server();
    if (problem != NULL) fail(problem);
    if (pthread_atfork(lock_for_fork, unlock_after_fork, reset_child) != 0) fail("cannot register the fork handlers");

    gum_init_embedded();
    /* Follow the process's global malloc binding, including an executable's own allocator, as rustc's jemalloc. */
    original_malloc = GSIZE_TO_POINTER(gum_module_find_global_export_by_name("malloc"));
    if (original_malloc == NULL) fail("malloc is not exported");
    GumInterceptor *interceptor = gum_interceptor_obtain();
    GumInvocationListener *listener = gum_make_call_listener(on_enter, on_leave, NULL, NULL);
    gum_interceptor_begin_transaction(interceptor);
    GumAttachReturn attached = gum_interceptor_attach(interceptor, GSIZE_TO_POINTER(original_malloc), listener, NULL);
    gum_interceptor_end_transaction(interceptor);
    if (attached != GUM_ATTACH_OK) fail("cannot attach to malloc");
}
