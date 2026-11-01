/*
 * Owns one unprivileged Bazel action and its host-selected attempt mounts.
 *
 * Production root invocation (statically linked trusted distribution image):
 *   HELPER --launch UID GID OWNER_CGROUP ROOT RUNTIME_MANIFEST IMAGE_PATH
 *     SOCKET SOCKET_DEVICE SOCKET_INODE CAPABILITY CWD ENV_MANIFEST
 *     STDOUT_FILE STDERR_FILE -- EXECUTABLE ORIGINAL_ARG...
 *
 * The host authenticates all arguments and the exact helper image before
 * launch. ROOT is a new host-owned minimal skeleton, with no symlinks in its
 * own paths and pre-created targets for runtime, socket, input and temporary
 * mappings. IMAGE_PATH is the separately sealed unprivileged glibc waiter name inside ROOT.
 * RUNTIME_MANIFEST contains host-selected NUL-terminated SOURCE,TARGET pairs:
 * exact immutable runtime trees/files, sealed JDK/classpath/Frida artifacts,
 * minimal /dev/{null,zero,random,urandom} and IMAGE_PATH. No broad home/credential
 * or worker tree is exposed. Sources must be canonical pinned host paths;
 * symlink spellings such as a host /bin symlink are rejected.
 * The waiter entry must identify its exact independently pinned immutable inode. The caller
 * supplies /proc and /dev/null targets. The helper seals every runtime bind
 * read-only, seals the root, creates private mount/PID namespaces, mounts a
 * namespace-local read-only proc, drops all credentials/capabilities, closes
 * host descriptors and executes only the pinned waiter with an empty loader environment.
 * ENV_MANIFEST must be empty in production. Only the authenticated Frida leader
 * receives the original action environment for its sole original execve.
 * STDOUT_FILE/STDERR_FILE are pre-created pinned regular host diagnostic files.
 * The caller must retain their complete contents and original offset semantics.
 *
 * Supervisor stdin/stdout are private, host-owned pipes. Framing is four-byte
 * big-endian positive length followed by UTF-8 payload, maximum 1048576 bytes.
 * Before namespace changes/forks the host authenticates the original helper
 * and places it in the exact helper cgroup, then exchanges INITIALIZE followed
 * by INITIALIZED. Setup replies LAUNCHED owner_host_pid owner_birth_ticks or
 * SETUP_FAILED. The latter retains this original supervisor until CLOSE proves
 * the pinned owner group empty, replies CLOSED 0 and exits successfully; it is
 * never an action result. Any missing/uncertain receipt retains host ownership.
 * After successful launch, commands:
 *   MAP<TAB>INPUT_TARGET<TAB>TMP_TARGET<TAB>LOWER<TAB>UPPER<TAB>WORK<TAB>TMP_SOURCE<TAB>ATTEMPT_CGROUP
 *   UNMAP
 *   CLOSE
 * Replies: MAPPED, UNMAPPED, CLOSED authentic_owner_status.
 * INPUT_TARGET/TMP_TARGET are disjoint original absolute action-visible names.
 * All other MAP paths are canonical host-selected source/cgroup paths. LOWER
 * is an immutable snapshot; UPPER/WORK/TMP_SOURCE must be fresh empty distinct
 * directories. The host prepares ownership/modes for the action UID. MAP pins
 * the exact empty attempt cgroup and mounts an overlay plus private tmp bind.
 * The same fresh temporary source is also bound at /tmp (NOSUID|NODEV), with
 * independent mount ownership. Original input/tmp/control/cwd/executable and
 * runtime target paths must be outside /tmp; hiding their ancestry is rejected.
 * The helper prepares an empty root-owned /tmp placeholder before root sealing;
 * neither alias ever exposes the host's shared /tmp tree or rewrites action env.
 * Java sends CREATE only after MAPPED, moves the PREPARED child into that same
 * cgroup before manager resume, and retains its native containment authority.
 * The successful merged view is ROOT + INPUT_TARGET in this supervisor's mount
 * namespace: the host reads /proc/SUPERVISOR_HOST_PID/root followed by that
 * complete absolute path while retaining the supervisor's PID/birth identity.
 * Before UNMAP, Java proves whole-attempt zero AND Frida helper closure and
 * freezes private output using the merged view; worker promotion follows FINAL.
 * The supervisor separately
 * checks pinned cgroup.events populated=0 and refuses busy ordinary unmounts.
 * CLOSE follows UNMAPPED and native owner's FINAL/exit. CLOSED also requires
 * original-owner wait status and persistent-owner cgroup zero. A helper error
 * permanently closes this control path, kills/collects the owner, checks zero
 * and attempts ordinary unmount; Java retains cleanup authority on any missing
 * receipt, uncertain cleanup or unexpected helper exit. Nothing infers zero
 * from a missing process, disconnected pipe or owner exit alone.
 *
 * Invocation:
 *   action-owner SOCKET CAPABILITY OUTPUT_COUNT OUTPUT... -- EXECUTABLE ARG...
 *
 * SOCKET is an absolute pathname Unix stream socket. CAPABILITY is exactly 64
 * lowercase hexadecimal characters identifying this invocation. Outputs are
 * nonempty relative file paths below the inherited working directory, with no
 * empty, ".", or ".." components. Directory outputs are unsupported. EXECUTABLE
 * and its arguments remain in the native argv; they are never text-serialized.
 * The host must inspect and authenticate the owner's complete executable image,
 * argv, environment, directory, credentials, namespace mapping, and containment
 * before granting an attempt. This program does not itself execute EXECUTABLE.
 *
 * The action environment and standard descriptors remain unchanged. Production
 * owner argv additionally accepts --cwd ABSOLUTE_CWD before OUTPUT_COUNT; each
 * prepared child re-resolves this absolute name after the current MAP rather
 * than retaining an underlay cwd. Production OUTPUT_COUNT must be zero because
 * the host's transactional workspace owns all output promotion/disposal.
 * A root controller authenticated through SO_PEERCRED receives the
 * invocation capability only after connection authentication. Each CREATE forks
 * a child in this sandbox with the same owner image, argv, environment and cwd.
 * That child's leader blocks in read(2); only the host's already authenticated
 * Frida manager may replace that leader with the original action executable.
 * The owner never writes a resume byte or grants an independent resume path.
 *
 * Wire framing is LinuxCargoTransport's four-byte big-endian positive length,
 * followed by exactly that many ASCII bytes, at most 1048576. Messages use single
 * spaces, canonical unsigned decimal, and no trailing whitespace or newline:
 *   owner -> host: OWNER capability local_pid birth_ticks
 *   host -> owner: CREATE ordinal
 *   owner -> host: PREPARED ordinal child_local_pid child_birth_ticks
 *   owner -> host: EXIT ordinal EXIT exit_code
 *   owner -> host: EXIT ordinal SIGNAL signal_number
 *   host -> owner: RESET ordinal
 *   owner -> host: RESET ordinal
 *   host -> owner: FINAL ordinal projected_status
 * Ordinals start at 1 and increase exactly once after each RESET acknowledgment.
 * RESET follows collected exit and the host's independent, authoritative
 * aborted-attempt, whole-cgroup-zero and reap decisions. It invalidates declared
 * partial file outputs before acknowledging. The host also guarantees exclusive
 * ownership of those output paths and ancestors through RESET: another actor
 * must not rename an opened ancestor outside the original working directory.
 * FINAL follows collected ordinary
 * exit; its status must equal exit_code or 128 + signal_number. Status alone
 * never selects retry. FINAL's status is returned unchanged to Bazel's waiter.
 *
 * Connect and each started frame have a 30-second monotonic deadline. Idle
 * waiting is unlimited; poll wakes at least every 100 ms to collect direct and
 * adopted children and notice loss of the controller. EOF, malformed messages,
 * invalid state, uncertain writes, path failures, or syscall errors close this
 * protocol permanently, kill the retained direct child, attempt bounded reaping,
 * print a diagnostic to inherited stderr, and return 125. No frame is replayed.
 * SIGINT/SIGTERM/SIGHUP use the same cleanup path. A SIGKILL or owner crash still
 * requires the host's cgroup cleanup; PDEATHSIG protects only the direct child.
 *
 * This process is a subreaper. waitpid establishes direct/adopted child status,
 * not whole-tree termination: descendants may still be parented below a live
 * process. The host must retain cgroup ownership through verified zero before
 * RESET/FINAL, including on connection loss. A failed bounded cleanup explicitly
 * reports its unresolved ownership; it is never a successful action result.
 */
#define _GNU_SOURCE
#include <errno.h>
#include <dirent.h>
#include <fcntl.h>
#include <inttypes.h>
#include <linux/capability.h>
#include <linux/close_range.h>
#include <linux/magic.h>
#include <linux/mount.h>
#include <limits.h>
#include <grp.h>
#include <poll.h>
#include <sched.h>
#include <signal.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/mount.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/statfs.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/auxv.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

/* Frida's sole original-image replacement resolves these exact libc symbols
 * from the unstripped static main module. Address-bearing used references keep
 * the implementations linked without introducing a native payload exec path. */
__attribute__((used)) static int (* volatile frida_execve_symbol)(const char *,
    char *const [], char *const []) = execve;
__attribute__((used)) static uid_t (* volatile frida_getuid_symbol)(void) = getuid;
__attribute__((used)) static gid_t (* volatile frida_getgid_symbol)(void) = getgid;

/** Fixed private compatibility name; never a bind of the host temporary tree. */
#define PRIVATE_TMP_PATH "/tmp"

/** Maximum complete payload shared with LinuxCargoTransport. */
#define MAX_FRAME_BYTES 1048576U
/** Milliseconds allowed for one connect, started frame, or cleanup attempt. */
#define OPERATION_TIMEOUT_MS 30000
/** Maximum milliseconds between child collection and controller checks. */
#define CHILD_POLL_MS 100

/** Indicates whether a handled termination signal requests fail-closed cleanup. */
static volatile sig_atomic_t termination_requested;

/** Owns the original directory and the sole current attempt child and gate. */
struct action_owner {
    int socket;
    bool pinned_endpoint;
    uint64_t endpoint_device;
    uint64_t endpoint_inode;
    int directory;
    int gate;
    pid_t child;
    uint64_t ordinal;
    bool collected;
    bool exit_reported;
    bool create_allowed;
    int projected_status;
    bool exited_normally;
    int termination_value;
    int output_count;
    char **outputs;
    /** Original absolute cwd, resolved anew in the current attempt mount. */
    const char *working_directory;
};

/** Records a termination request; the normal control loop performs cleanup. */
static void request_termination(int signal_number)
{
    termination_requested = signal_number;
}

/**
 * Reports a permanently failed operation without printing the capability.
 * Returns -1; the top-level owner remains responsible for cleanup.
 */
static int fail(const char *operation)
{
    int failure_number = errno;
    fprintf(stderr, "action-owner: %s: %s\n", operation, strerror(failure_number));
    return -1;
}

/** Reports an invalid caller input or wire state and returns -1. */
static int reject(const char *reason)
{
    fprintf(stderr, "action-owner: %s\n", reason);
    return -1;
}

/**
 * Closes a newly acquired private descriptor once. Returns zero on success,
 * or -1 with a diagnostic; an uncertain close is never retried.
 */
static int close_private(int descriptor)
{
    if (close(descriptor) < 0)
        return fail("close private descriptor");
    return 0;
}

/**
 * Rejects root, saved-UID elevation, and every inheritable/permitted/effective
 * Linux capability without changing credentials. Returns zero only when all
 * three UIDs are equal and nonzero and both capability words contain zero.
 */
static int require_unprivileged_owner(void)
{
    uid_t real;
    uid_t effective;
    uid_t saved;
    if (getresuid(&real, &effective, &saved) < 0)
        return fail("inspect action-owner UID state");
    if (real == 0 || real != effective || real != saved)
        return reject("action owner must have equal nonzero real, effective, and saved UIDs");
    struct __user_cap_header_struct header = {
        .version = _LINUX_CAPABILITY_VERSION_3, .pid = 0
    };
    struct __user_cap_data_struct capabilities[2] = { { 0 }, { 0 } };
    if (syscall(SYS_capget, &header, capabilities) < 0)
        return fail("inspect action-owner capability state");
    for (size_t index = 0; index < 2; ++index) {
        bool has_capabilities = capabilities[index].effective ||
            capabilities[index].permitted || capabilities[index].inheritable;
        if (has_capabilities)
            return reject("action owner must have no effective, permitted, or inheritable capabilities");
    }
    return 0;
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
 * Parses a canonical unsigned decimal token bounded by maximum into value.
 * Returns zero on success, or -1 for empty, noncanonical, or out-of-range text.
 */
static int parse_decimal(const char *text, uint64_t maximum, uint64_t *value)
{
    if (text[0] == '\0')
        return reject("decimal token is empty");
    if (text[0] == '0' && text[1] != '\0')
        return reject("decimal token has a leading zero");

    uint64_t result = 0;
    for (const unsigned char *cursor = (const unsigned char *) text; *cursor; ++cursor) {
        if (*cursor < '0' || *cursor > '9')
            return reject("decimal token contains a nondigit");
        uint64_t digit = *cursor - '0';
        if (digit > maximum || result > ((maximum - digit) / 10))
            return reject("decimal token exceeds its permitted range");
        result = (result * 10) + digit;
    }
    *value = result;
    return 0;
}

/** Indicates whether capability is exactly 64 lowercase hexadecimal bytes. */
static bool valid_capability(const char *capability)
{
    if (strlen(capability) != 64)
        return false;
    for (size_t index = 0; index < 64; ++index) {
        unsigned char character = (unsigned char) capability[index];
        bool hexadecimal = (character >= '0' && character <= '9') ||
            (character >= 'a' && character <= 'f');
        if (!hexadecimal)
            return false;
    }
    return true;
}

/**
 * Checks relative file-output syntax before connecting or creating children.
 * Returns zero for nonempty components excluding "." and "..", or -1.
 */
static int validate_output(const char *path)
{
    if (path[0] == '\0' || path[0] == '/' || strlen(path) >= PATH_MAX)
        return reject("output must be a nonempty relative path shorter than PATH_MAX");
    const char *component = path;
    for (const char *cursor = path;; ++cursor) {
        if (*cursor == '/' || *cursor == '\0') {
            size_t length = (size_t) (cursor - component);
            bool unsafe_component = length == 0 ||
                (length == 1 && component[0] == '.') ||
                (length == 2 && component[0] == '.' && component[1] == '.');
            if (unsafe_component)
                return reject("output path contains an empty, dot, or parent component");
            if (*cursor == '\0')
                break;
            component = cursor + 1;
        }
    }
    return 0;
}

/**
 * Reads Linux /proc/PID/stat field 22 without treating spaces or ')' in comm as
 * field delimiters. Returns zero with positive birth ticks, or reports failure.
 * PID must still denote this owner or an unreaped child retained by this owner.
 */
static int read_birth(pid_t process, uint64_t *birth)
{
    char path[64];
    int path_length = snprintf(path, sizeof(path), "/proc/%ld/stat", (long) process);
    if (path_length < 0 || (size_t) path_length >= sizeof(path))
        return reject("process stat path cannot be represented");
    int descriptor = open(path, O_RDONLY | O_CLOEXEC);
    if (descriptor < 0)
        return fail("open retained process stat");

    char contents[8192];
    ssize_t length;
    do {
        length = read(descriptor, contents, sizeof(contents) - 1);
    } while (length < 0 && errno == EINTR && termination_requested == 0);
    int read_error = errno;
    if (close(descriptor) < 0)
        return fail("close retained process stat");
    if (length <= 0 || (size_t) length == sizeof(contents) - 1) {
        errno = read_error;
        return reject("process stat is empty, unreadable, or exceeds the bounded buffer");
    }
    contents[length] = '\0';
    char *closing = strrchr(contents, ')');
    if (closing == NULL || closing[1] != ' ')
        return reject("process stat has no complete command field");
    char *cursor = closing + 2;
    for (int field = 3; field < 22; ++field) {
        char *separator = strchr(cursor, ' ');
        if (separator == NULL)
            return reject("process stat has no start-time field");
        cursor = separator + 1;
    }
    char *separator = strchr(cursor, ' ');
    if (separator == NULL)
        return reject("process stat start-time field is incomplete");
    *separator = '\0';
    if (parse_decimal(cursor, UINT64_MAX, birth) < 0)
        return -1;
    if (*birth == 0)
        return reject("process birth ticks must be positive");
    return 0;
}

/**
 * Collects every currently waitable child without blocking. Direct-child status
 * is retained exactly once and its pipe write end closes only after collection.
 * Returns zero, or -1 on wait/close failure or unsupported child termination.
 */
static int collect_children(struct action_owner *owner)
{
    for (;;) {
        int status;
        pid_t process = waitpid(-1, &status, WNOHANG);
        if (process == 0 || (process < 0 && errno == ECHILD))
            return 0;
        if (process < 0) {
            if (errno == EINTR)
                continue;
            return fail("collect owned or adopted child");
        }
        if (process == owner->child) {
            if (WIFEXITED(status)) {
                owner->exited_normally = true;
                owner->termination_value = WEXITSTATUS(status);
                owner->projected_status = owner->termination_value;
            } else if (WIFSIGNALED(status)) {
                owner->exited_normally = false;
                owner->termination_value = WTERMSIG(status);
                if (owner->termination_value < 1 || owner->termination_value > 64)
                    return reject("collected Linux signal is outside 1 through 64");
                owner->projected_status = 128 + owner->termination_value;
            } else
                return reject("waitpid returned an unsupported direct-child status");
            owner->collected = true;
            if (owner->gate >= 0) {
                int gate = owner->gate;
                owner->gate = -1;
                if (close(gate) < 0)
                    return fail("close collected child's gate");
            }
        }
    }
}

/**
 * Waits for socket readiness in at most 100-ms slices, collecting children.
 * A negative deadline means idle waiting; otherwise deadline is absolute
 * monotonic milliseconds. Returns zero ready, -1 on failure or deadline.
 */
static int await_socket(struct action_owner *owner, short events, int64_t deadline)
{
    for (;;) {
        if (termination_requested)
            return reject("termination signal requested protocol cleanup");
        if (collect_children(owner) < 0)
            return -1;
        int timeout = CHILD_POLL_MS;
        if (deadline >= 0) {
            int64_t now = monotonic_milliseconds();
            if (now < 0)
                return -1;
            int64_t remaining = deadline - now;
            if (remaining <= 0)
                return reject("controller operation exceeded its 30-second deadline");
            if (remaining < timeout)
                timeout = (int) remaining;
        }
        struct pollfd descriptor = { .fd = owner->socket, .events = events };
        int result = poll(&descriptor, 1, timeout);
        if (result < 0) {
            if (errno == EINTR)
                continue;
            return fail("poll controller connection");
        }
        if (result == 0)
            continue;
        if ((descriptor.revents & events) != 0)
            return 0;
        if ((descriptor.revents & (POLLERR | POLLHUP | POLLNVAL)) != 0)
            return reject("controller connection closed or failed");
    }
}

/**
 * Transfers exactly length bytes within one caller-supplied complete-frame
 * deadline, servicing children between partial transfers. write_operation sends
 * without SIGPIPE; false reads. EOF and uncertain transfers return -1.
 */
static int transfer_bytes(struct action_owner *owner, void *bytes, size_t length,
    bool write_operation, int64_t deadline)
{
    size_t transferred = 0;
    while (transferred < length) {
        short events = write_operation ? POLLOUT : POLLIN;
        if (await_socket(owner, events, deadline) < 0)
            return -1;
        ssize_t count;
        if (write_operation)
            count = send(owner->socket, (char *) bytes + transferred,
                length - transferred, MSG_NOSIGNAL);
        else
            count = recv(owner->socket, (char *) bytes + transferred,
                length - transferred, 0);
        if (count < 0) {
            if (errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK)
                continue;
            return fail(write_operation ? "send controller frame" : "read controller frame");
        }
        if (count == 0)
            return reject("controller closed during a frame");
        transferred += (size_t) count;
    }
    return 0;
}

/**
 * Sends one ASCII payload with one shared header/payload deadline. Returns zero,
 * or -1 permanently; callers must never resend after a partial write.
 */
static int send_frame(struct action_owner *owner, const char *payload)
{
    size_t length = strlen(payload);
    if (length == 0 || length > MAX_FRAME_BYTES)
        return reject("outbound frame length is outside 1 through 1048576");
    int64_t now = monotonic_milliseconds();
    if (now < 0)
        return -1;
    int64_t deadline = now + OPERATION_TIMEOUT_MS;
    unsigned char header[4] = {
        (unsigned char) (length >> 24), (unsigned char) (length >> 16),
        (unsigned char) (length >> 8), (unsigned char) length
    };
    if (transfer_bytes(owner, header, sizeof(header), true, deadline) < 0)
        return -1;
    return transfer_bytes(owner, (void *) payload, length, true, deadline);
}

/**
 * Reads one bounded frame into a newly allocated NUL-terminated buffer owned by
 * the caller. Rejects nonprintable/non-ASCII bytes. Idle waiting precedes the
 * shared 30-second header/payload deadline. Returns NULL permanently on failure.
 */
static char *read_frame(struct action_owner *owner)
{
    if (await_socket(owner, POLLIN, -1) < 0)
        return NULL;
    int64_t now = monotonic_milliseconds();
    if (now < 0)
        return NULL;
    int64_t deadline = now + OPERATION_TIMEOUT_MS;
    unsigned char header[4];
    if (transfer_bytes(owner, header, sizeof(header), false, deadline) < 0)
        return NULL;
    uint32_t length = ((uint32_t) header[0] << 24) |
        ((uint32_t) header[1] << 16) | ((uint32_t) header[2] << 8) | header[3];
    if (length == 0 || length > MAX_FRAME_BYTES) {
        reject("inbound frame length is outside 1 through 1048576");
        return NULL;
    }
    char *payload = malloc((size_t) length + 1);
    if (payload == NULL) {
        fail("allocate bounded controller frame");
        return NULL;
    }
    if (transfer_bytes(owner, payload, length, false, deadline) < 0) {
        free(payload);
        return NULL;
    }
    for (uint32_t index = 0; index < length; ++index) {
        unsigned char character = (unsigned char) payload[index];
        if (character < 32 || character > 126) {
            free(payload);
            reject("controller frame contains a nonprintable or non-ASCII byte");
            return NULL;
        }
    }
    payload[length] = '\0';
    return payload;
}

/**
 * Checks the exact host-created socket inode without following symbolic links.
 * The trusted host must pin this identity before launch and verify that every
 * endpoint-directory ancestor is immutable in both host and action namespaces.
 * The action owner has already proved it holds no capabilities. Before/after
 * inode checks alone do not establish that pathname-replacement exclusion.
 */
static int require_endpoint(const struct action_owner *owner, const char *endpoint)
{
    struct stat observed;
    if (lstat(endpoint, &observed) < 0)
        return fail("inspect pinned controller socket");
    if (!S_ISSOCK(observed.st_mode) || (uint64_t) observed.st_dev != owner->endpoint_device ||
        (uint64_t) observed.st_ino != owner->endpoint_inode)
        return reject("controller socket inode differs from trusted host launch");
    return 0;
}

/**
 * Connects a nonblocking close-on-exec Unix socket within 30 seconds, then
 * authenticates effective root peer credentials or the immutable host-pinned
 * endpoint before any capability is sent. Host root is unmapped inside a Bazel
 * user namespace, so namespace-local overflow credentials never authorize it.
 * Returns zero with ownership retained in owner, or -1 requiring cleanup.
 */
static int connect_controller(struct action_owner *owner, const char *endpoint)
{
    if (owner->pinned_endpoint && require_endpoint(owner, endpoint) < 0)
        return -1;
    struct sockaddr_un address = { .sun_family = AF_UNIX };
    size_t length = strlen(endpoint);
    if (endpoint[0] != '/' || length >= sizeof(address.sun_path))
        return reject("controller socket path must be absolute and shorter than 108 bytes");
    memcpy(address.sun_path, endpoint, length + 1);
    owner->socket = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
    if (owner->socket < 0)
        return fail("create controller socket");
    if (connect(owner->socket, (struct sockaddr *) &address, sizeof(address)) < 0) {
        if (errno != EINPROGRESS && errno != EAGAIN)
            return fail("connect trusted controller endpoint");
        int64_t now = monotonic_milliseconds();
        if (now < 0 || await_socket(owner, POLLOUT, now + OPERATION_TIMEOUT_MS) < 0)
            return -1;
        int connection_error = 0;
        socklen_t error_length = sizeof(connection_error);
        if (getsockopt(owner->socket, SOL_SOCKET, SO_ERROR, &connection_error, &error_length) < 0)
            return fail("read controller connection result");
        if (connection_error) {
            errno = connection_error;
            return fail("finish trusted controller connection");
        }
    }
    struct ucred credentials;
    socklen_t credentials_length = sizeof(credentials);
    if (getsockopt(owner->socket, SOL_SOCKET, SO_PEERCRED, &credentials, &credentials_length) < 0)
        return fail("capture controller kernel credentials");
    if (credentials_length != sizeof(credentials))
        return reject("controller kernel peer credential size differs");
    if (owner->pinned_endpoint) {
        if (require_endpoint(owner, endpoint) < 0)
            return -1;
    } else if (credentials.uid != 0) {
        return reject("controller kernel peer credentials do not identify UID 0");
    }
    return 0;
}

/**
 * Forks the next child with unchanged owner argv/env/cwd and inherited stdio.
 * The child closes the controller endpoint, establishes parent-death protection,
 * reports gate readiness through a private pipe, and blocks on its leader.
 * Returns zero after PREPARED, or -1 with ownership retained for cleanup.
 */
static int create_attempt(struct action_owner *owner)
{
    int gate[2];
    int ready[2];
    if (pipe2(gate, O_CLOEXEC) < 0)
        return fail("create child hold pipe");
    if (pipe2(ready, O_CLOEXEC) < 0) {
        int failure_number = errno;
        close_private(gate[0]);
        close_private(gate[1]);
        errno = failure_number;
        return fail("create child readiness pipe");
    }
    pid_t parent = getpid();
    pid_t child = fork();
    if (child == 0) {
        int descriptor_failure = 0;
        if (close_private(gate[1]) < 0)
            descriptor_failure = -1;
        if (close_private(ready[0]) < 0)
            descriptor_failure = -1;
        if (close_private(owner->socket) < 0)
            descriptor_failure = -1;
        if (close_private(owner->directory) < 0)
            descriptor_failure = -1;
        if (descriptor_failure < 0)
            _exit(125);
        if (prctl(PR_SET_PDEATHSIG, (unsigned long) SIGKILL, 0UL, 0UL, 0UL) < 0) {
            fail("install held child's parent-death signal");
            _exit(125);
        }
        if (getppid() != parent) {
            reject("held child's original parent exited before protection was installed");
            _exit(125);
        }
        /* An inherited cwd descriptor names the previous underlay even after a
         * fresh overlay is mounted. Resolve the original name after each MAP. */
        if (owner->working_directory != NULL && chdir(owner->working_directory) < 0) {
            fail("resolve original cwd in current attempt mapping");
            _exit(125);
        }
        unsigned char initialized = 1;
        if (write(ready[1], &initialized, 1) != 1) {
            fail("publish held child's gate readiness");
            _exit(125);
        }
        if (close_private(ready[1]) < 0)
            _exit(125);

        /* The parent keeps the write end until waitpid. No owner code writes a
         * byte. EOF/error is failure, never permission to execute the action. */
        unsigned char forbidden_resume;
        for (;;) {
            ssize_t result = read(gate[0], &forbidden_resume, 1);
            if (result < 0 && errno == EINTR)
                continue;
            if (result < 0)
                fail("wait on held child's gate");
            else
                reject("held child's gate changed without host leader replacement");
            _exit(125);
        }
    }
    int fork_error = errno;
    int descriptor_failure = 0;
    if (close_private(gate[0]) < 0)
        descriptor_failure = -1;
    if (close_private(ready[1]) < 0)
        descriptor_failure = -1;
    if (child < 0) {
        close_private(gate[1]);
        close_private(ready[0]);
        errno = fork_error;
        return fail("fork held action leader");
    }
    owner->child = child;
    owner->gate = gate[1];
    owner->collected = false;
    owner->exit_reported = false;
    owner->create_allowed = false;
    if (descriptor_failure < 0) {
        close_private(ready[0]);
        return -1;
    }

    int64_t now = monotonic_milliseconds();
    int outcome = 0;
    if (now < 0)
        outcome = -1;
    int64_t deadline = now + OPERATION_TIMEOUT_MS;
    while (outcome == 0) {
        if (termination_requested) {
            outcome = reject("termination requested while preparing child");
            break;
        }
        struct pollfd descriptors[2] = {
            { .fd = ready[0], .events = POLLIN },
            { .fd = owner->socket, .events = POLLIN }
        };
        int64_t current = monotonic_milliseconds();
        if (current < 0 || current >= deadline) {
            outcome = reject("child gate readiness exceeded its deadline");
            break;
        }
        int remaining = (int) (deadline - current);
        int timeout = remaining < CHILD_POLL_MS ? remaining : CHILD_POLL_MS;
        int result = poll(descriptors, 2, timeout);
        if (result < 0) {
            if (errno == EINTR)
                continue;
            outcome = fail("poll child gate readiness");
            break;
        }
        if (descriptors[1].revents) {
            outcome = reject("controller closed or sent a command before PREPARED");
            break;
        }
        if (descriptors[0].revents) {
            unsigned char initialized;
            ssize_t count;
            do {
                count = read(ready[0], &initialized, 1);
            } while (count < 0 && errno == EINTR && termination_requested == 0);
            if (count == 1 && initialized == 1)
                break;
            if (count < 0)
                outcome = fail("read child gate readiness");
            else
                outcome = reject("child did not publish gate readiness before closing its pipe");
            break;
        }
    }
    if (close(ready[0]) < 0)
        return fail("close child readiness pipe");
    if (outcome < 0)
        return -1;
    uint64_t birth;
    if (read_birth(child, &birth) < 0)
        return -1;
    char message[160];
    int length = snprintf(message, sizeof(message), "PREPARED %" PRIu64 " %ld %" PRIu64,
        owner->ordinal, (long) child, birth);
    if (length < 0 || (size_t) length >= sizeof(message))
        return reject("prepared child identity cannot be represented");
    return send_frame(owner, message);
}

/**
 * Unlinks one declared file relative to the retained original working directory.
 * Each ancestor is opened O_NOFOLLOW|O_DIRECTORY; a leaf symlink is unlinked
 * without following it. Missing paths succeed; directories and special files
 * fail. Returns zero, or -1; earlier outputs may already have been removed.
 */
static int invalidate_output(struct action_owner *owner, const char *path)
{
    char *copy = strdup(path);
    if (copy == NULL)
        return fail("copy declared output path");
    int directory = fcntl(owner->directory, F_DUPFD_CLOEXEC, 3);
    if (directory < 0) {
        free(copy);
        return fail("retain output directory");
    }
    int outcome = 0;
    bool missing_ancestor = false;
    char *component = copy;
    for (;;) {
        char *separator = strchr(component, '/');
        if (separator == NULL)
            break;
        *separator = '\0';
        int next = openat(directory, component, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
        if (next < 0) {
            if (errno == ENOENT) {
                missing_ancestor = true;
                break;
            }
            outcome = fail("open declared output ancestor without following symlinks");
            break;
        }
        if (close(directory) < 0) {
            close_private(next);
            outcome = fail("close declared output ancestor");
            directory = -1;
            break;
        }
        directory = next;
        component = separator + 1;
    }
    if (outcome == 0 && !missing_ancestor) {
        struct stat metadata;
        if (fstatat(directory, component, &metadata, AT_SYMLINK_NOFOLLOW) < 0) {
            if (errno != ENOENT)
                outcome = fail("inspect declared output leaf without following symlinks");
        } else if (S_ISREG(metadata.st_mode) || S_ISLNK(metadata.st_mode)) {
            if (unlinkat(directory, component, 0) < 0 && errno != ENOENT)
                outcome = fail("unlink declared partial file output");
        } else {
            outcome = reject("declared output leaf is a directory or special file");
        }
    }
    if (directory >= 0 && close(directory) < 0)
        outcome = fail("close retained output ancestor");
    free(copy);
    return outcome;
}

/**
 * Parses one command's single-space tokens in place. Returns 2 or 3 tokens,
 * or -1 for empty, duplicate, trailing, or excessive separators/tokens.
 */
static int split_command(char *payload, char **tokens)
{
    int count = 1;
    tokens[0] = payload;
    if (payload[0] == '\0' || payload[0] == ' ')
        return reject("controller command has an empty first token");
    for (char *cursor = payload; *cursor; ++cursor) {
        if (*cursor == ' ') {
            if (cursor[1] == '\0' || cursor[1] == ' ' || count == 3)
                return reject("controller command has invalid token separators");
            *cursor = '\0';
            tokens[count++] = cursor + 1;
        }
    }
    if (count < 2)
        return reject("controller command has no ordinal");
    return count;
}

/**
 * Performs one authenticated controller transition. Returns 0 to continue,
 * 1 with final_status for FINAL, or -1 permanently on invalid state or failure.
 * RESET's host command supplies abort/cgroup-zero authority; this owner only
 * validates local collection and invalidates partial outputs before its ack.
 */
static int handle_command(struct action_owner *owner, char *payload, int *final_status)
{
    char *tokens[3];
    int count = split_command(payload, tokens);
    if (count < 0)
        return -1;
    uint64_t ordinal;
    if (parse_decimal(tokens[1], UINT64_MAX, &ordinal) < 0)
        return -1;
    if (ordinal == 0 || ordinal != owner->ordinal)
        return reject("controller ordinal is not the current positive attempt ordinal");

    if (strcmp(tokens[0], "CREATE") == 0 && count == 2) {
        if (!owner->create_allowed)
            return reject("CREATE requires initial state or a completed RESET acknowledgment");
        return create_attempt(owner);
    }
    if (strcmp(tokens[0], "RESET") == 0 && count == 2) {
        if (!owner->collected || !owner->exit_reported || owner->create_allowed)
            return reject("RESET requires one collected attempt awaiting a host decision");
        if (owner->ordinal == UINT64_MAX)
            return reject("retry ordinal would overflow");
        for (int index = 0; index < owner->output_count; ++index)
            if (invalidate_output(owner, owner->outputs[index]) < 0)
                return -1;
        char message[64];
        int length = snprintf(message, sizeof(message), "RESET %" PRIu64, owner->ordinal);
        if (length < 0 || (size_t) length >= sizeof(message))
            return reject("reset ordinal cannot be represented");
        if (send_frame(owner, message) < 0)
            return -1;
        ++owner->ordinal;
        owner->child = 0;
        owner->collected = false;
        owner->exit_reported = false;
        owner->create_allowed = true;
        return 0;
    }
    if (strcmp(tokens[0], "FINAL") == 0 && count == 3) {
        if (!owner->collected || !owner->exit_reported || owner->create_allowed)
            return reject("FINAL requires a collected attempt awaiting a host decision");
        uint64_t status;
        if (parse_decimal(tokens[2], 255, &status) < 0)
            return -1;
        if ((int) status != owner->projected_status)
            return reject("FINAL status differs from the collected attempt status projection");
        *final_status = (int) status;
        return 1;
    }
    return reject("controller command kind or arity is unsupported");
}

/**
 * Kills the retained unreaped direct child, reaps available adopted children,
 * and waits up to 30 seconds for all children to disappear. Returns zero only
 * when waitpid reports ECHILD; this still does not establish host cgroup-zero.
 */
static int cleanup_children(struct action_owner *owner)
{
    if (owner->child > 0 && !owner->collected) {
        if (kill(owner->child, SIGKILL) < 0 && errno != ESRCH)
            fail("kill retained direct child during cleanup");
    }
    int64_t now = monotonic_milliseconds();
    if (now < 0)
        return -1;
    int64_t deadline = now + OPERATION_TIMEOUT_MS;
    for (;;) {
        if (collect_children(owner) < 0)
            return -1;
        int status;
        pid_t remaining = waitpid(-1, &status, WNOHANG);
        if (remaining < 0 && errno == ECHILD)
            return 0;
        if (remaining < 0 && errno != EINTR)
            return fail("inspect remaining cleanup children");
        /* A second wait must never consume a direct-child result that the normal
         * collector needs: cleanup's only next decision is permanent failure. */
        int64_t current = monotonic_milliseconds();
        if (current < 0)
            return -1;
        if (current >= deadline)
            return reject("cleanup still owns live children; host cgroup cleanup is required");
        struct timespec pause = { .tv_sec = 0, .tv_nsec = CHILD_POLL_MS * 1000000L };
        if (nanosleep(&pause, NULL) < 0 && errno != EINTR)
            return fail("pause bounded child cleanup");
    }
}

/**
 * Releases owner descriptors exactly once, reporting each failed close.
 * Returns zero on success, or -1; close failures are never retried.
 */
static int close_owner(struct action_owner *owner)
{
    int outcome = 0;
    int *descriptors[] = { &owner->socket, &owner->gate, &owner->directory };
    for (size_t index = 0; index < sizeof(descriptors) / sizeof(descriptors[0]); ++index) {
        int descriptor = *descriptors[index];
        *descriptors[index] = -1;
        if (descriptor >= 0 && close(descriptor) < 0) {
            fail("close owned descriptor");
            outcome = -1;
        }
    }
    return outcome;
}

/**
 * Validates invocation, authenticates the controller, and owns the native action
 * lifecycle. Returns the authenticated FINAL status on ordinary completion;
 * returns 125 after reporting input/protocol/syscall/cleanup failure.
 */
static int run_owner(int argc, char **argv)
{
    struct action_owner owner = {
        .socket = -1, .directory = -1, .gate = -1,
        .ordinal = 1, .create_allowed = true
    };
    int outcome = 125;
    if (argc < 6) {
        reject("expected SOCKET CAPABILITY OUTPUT_COUNT OUTPUT... -- EXECUTABLE ARG...");
        return outcome;
    }
    if (require_unprivileged_owner() < 0)
        return outcome;
    if (!valid_capability(argv[2])) {
        reject("invocation capability must contain exactly 64 lowercase hexadecimal characters");
        return outcome;
    }
    int count_index = 3;
    if (strcmp(argv[3], "--socket-identity") == 0) {
        if (argc < 9 || parse_decimal(argv[4], UINT64_MAX, &owner.endpoint_device) < 0 ||
            parse_decimal(argv[5], UINT64_MAX, &owner.endpoint_inode) < 0 || owner.endpoint_inode == 0)
            return outcome;
        owner.pinned_endpoint = true;
        count_index = 6;
    }
    if (argc > count_index && strcmp(argv[count_index], "--cwd") == 0) {
        if (argc <= count_index + 3 || argv[count_index + 1][0] != '/') {
            reject("production cwd must be an absolute path");
            return outcome;
        }
        owner.working_directory = argv[count_index + 1];
        count_index += 2;
    }
    uint64_t output_count;
    if (parse_decimal(argv[count_index], (uint64_t) argc, &output_count) < 0)
        return outcome;
    if (output_count > (uint64_t) (argc - count_index - 3)) {
        reject("output count leaves no separator and original executable");
        return outcome;
    }
    int separator = count_index + 1 + (int) output_count;
    if (strcmp(argv[separator], "--") != 0 || argv[separator + 1][0] == '\0') {
        reject("outputs must be followed by -- and a nonempty original executable");
        return outcome;
    }
    owner.output_count = (int) output_count;
    if (owner.working_directory != NULL && owner.output_count != 0) {
        reject("production outputs belong to the host promotion journal; output count must be zero");
        return outcome;
    }
    owner.outputs = argv + count_index + 1;
    for (int index = 0; index < owner.output_count; ++index)
        if (validate_output(owner.outputs[index]) < 0)
            return outcome;

    struct sigaction action = { .sa_handler = request_termination };
    sigemptyset(&action.sa_mask);
    if (sigaction(SIGTERM, &action, NULL) < 0 || sigaction(SIGINT, &action, NULL) < 0 ||
        sigaction(SIGHUP, &action, NULL) < 0) {
        fail("install action-owner termination handlers");
        return outcome;
    }
    struct sigaction child_status = { .sa_handler = SIG_DFL };
    sigemptyset(&child_status.sa_mask);
    if (sigaction(SIGCHLD, &child_status, NULL) < 0) {
        fail("establish waitable child termination status");
        return outcome;
    }
    if (prctl(PR_SET_CHILD_SUBREAPER, 1UL, 0UL, 0UL, 0UL) < 0) {
        fail("establish action-owner child subreaper");
        return outcome;
    }
    /* A production owner remains at the immutable namespace root. Its child
     * resolves the original action cwd after each MAP; holding that mount as the
     * owner's cwd would both invalidate authentication and prevent UNMAP. */
    if (owner.working_directory != NULL && chdir("/") < 0) {
        fail("resolve immutable owner root before registration");
        return outcome;
    }
    /* Payloads share the unprivileged UID but never gain authority over the
     * trusted owner protocol or its descriptors through ptrace/proc access.
     * Privileged host instrumentation can still attach the blocked child. */
    if (owner.working_directory != NULL && prctl(PR_SET_DUMPABLE, 0UL, 0UL, 0UL, 0UL) < 0) {
        fail("protect native owner from same-uid payload inspection");
        return outcome;
    }
    owner.directory = open(".", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (owner.directory < 0) {
        fail("retain original action working directory");
        return outcome;
    }
    if (connect_controller(&owner, argv[1]) < 0)
        goto cleanup;
    uint64_t birth;
    if (read_birth(getpid(), &birth) < 0)
        goto cleanup;
    char registration[192];
    int registration_length = snprintf(registration, sizeof(registration),
        "OWNER %s %ld %" PRIu64, argv[2], (long) getpid(), birth);
    if (registration_length < 0 || (size_t) registration_length >= sizeof(registration)) {
        reject("owner identity cannot be represented");
        goto cleanup;
    }
    if (send_frame(&owner, registration) < 0)
        goto cleanup;

    for (;;) {
        if (collect_children(&owner) < 0)
            break;
        if (owner.collected && !owner.exit_reported) {
            char result[96];
            const char *kind = owner.exited_normally ? "EXIT" : "SIGNAL";
            int value = owner.termination_value;
            int length = snprintf(result, sizeof(result), "EXIT %" PRIu64 " %s %d",
                owner.ordinal, kind, value);
            if (length < 0 || (size_t) length >= sizeof(result)) {
                reject("collected attempt result cannot be represented");
                break;
            }
            if (send_frame(&owner, result) < 0)
                break;
            owner.exit_reported = true;
        }
        /* Poll once before read_frame so a newly collected exit is emitted even
         * while the controller is waiting for that exit rather than sending. */
        struct pollfd descriptor = { .fd = owner.socket, .events = POLLIN };
        int ready = poll(&descriptor, 1, CHILD_POLL_MS);
        if (termination_requested) {
            reject("termination signal requested protocol cleanup");
            break;
        }
        if (ready < 0) {
            if (errno == EINTR)
                continue;
            fail("poll next controller command");
            break;
        }
        if (ready == 0)
            continue;
        char *command = read_frame(&owner);
        if (command == NULL)
            break;
        int final_status = 125;
        int decision = handle_command(&owner, command, &final_status);
        free(command);
        if (decision < 0)
            break;
        if (decision == 1) {
            outcome = final_status;
            break;
        }
    }

cleanup:
    /* Closing control first lets the host run cgroup teardown while this owner
     * is collecting children. The socket is never reused during cleanup. */
    if (owner.socket >= 0) {
        int connection = owner.socket;
        owner.socket = -1;
        if (close_private(connection) < 0)
            outcome = 125;
    }
    if (cleanup_children(&owner) < 0)
        outcome = 125;
    if (close_owner(&owner) < 0)
        outcome = 125;
    return outcome;
}

/**
 * Owns the root-side namespace supervisor. The Java caller retains this process
 * through every mount and cleanup lease. Its child is the unprivileged owner;
 * only that owner's held children may be moved into an attempt cgroup.
 */
struct namespace_supervisor {
    /** Host-selected unprivileged action credentials. */
    uid_t uid;
    gid_t gid;
    /** Pinned immutable skeleton; all target names resolve below this root. */
    int root;
    const char *root_path;
    /** Separate persistent-owner group, never used to prove attempt zero. */
    int owner_group;
    /** Pidfd of the root supervisor, inherited solely across owner setup. */
    int parent_identity;
    /** Exact trusted helper name mounted inside the root for image checks. */
    const char *image_path;
    int image_fd;
    /** Exact trusted immutable runtime names that writable mappings may not hide. */
    char **runtime_targets;
    size_t runtime_count;
    /** Pinned current attempt group; retained until verified unmount. */
    int attempt_group;
    /** Original absolute input/tmp mount names inside the sealed root. */
    char input_target[PATH_MAX];
    char temporary_target[PATH_MAX];
    /** Independent private /tmp alias of the same fresh attempt temporary source. */
    char temporary_alias_target[PATH_MAX];
    /** Open lower/upper/work/tmp descriptors keep mapping sources attached. */
    int sources[4];
    /** Each flag records an actual successful mount, including partial MAP. */
    bool input_mounted;
    bool temporary_mounted;
    bool temporary_alias_mounted;
    /** Host PID and authentic wait result of the PID-namespace init owner. */
    pid_t owner;
    bool collected;
    int owner_status;
};

/**
 * Rejects a target at, below, or enclosing the fixed private /tmp alias.
 * These comparisons are component-boundary comparisons; /tmp-other is unrelated.
 * Callers separately require absolute normalized paths before using them.
 */
static bool conflicts_private_temporary(const char *path)
{
    return strcmp(path, "/") == 0 || strcmp(path, PRIVATE_TMP_PATH) == 0 ||
        strncmp(path, PRIVATE_TMP_PATH "/", sizeof(PRIVATE_TMP_PATH)) == 0;
}


/**
 * Opens an absolute path component by component without following links. Every
 * ancestor must exclude writes by the action UID, group, and other users. A
 * mutable leaf is allowed only for an upper/work/tmp source, never a host target.
 * Returns a caller-owned descriptor, or -1 before any mount or process change.
 */
static int open_host_path(const char *path, uid_t action_uid, bool directory,
    bool protected_leaf)
{
    if (path[0] != '/' || path[1] == '\0' || strlen(path) >= PATH_MAX)
        return reject("host path must be absolute, non-root, and shorter than PATH_MAX");
    char copy[PATH_MAX];
    memcpy(copy, path + 1, strlen(path));
    int parent = open("/", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (parent < 0)
        return fail("open host path root");
    char *component = copy;
    for (;;) {
        char *separator = strchr(component, '/');
        bool leaf = separator == NULL;
        if (separator != NULL)
            *separator = '\0';
        if (component[0] == '\0' || strcmp(component, ".") == 0 ||
            strcmp(component, "..") == 0) {
            close_private(parent);
            return reject("host path contains an empty, dot, or parent component");
        }
        int flags = O_PATH | O_NOFOLLOW | O_CLOEXEC;
        if (!leaf || directory)
            flags |= O_DIRECTORY;
        int next = openat(parent, component, flags);
        int open_error = errno;
        if (close_private(parent) < 0) {
            if (next >= 0)
                close_private(next);
            return -1;
        }
        if (next < 0) {
            errno = open_error;
            return fail("open host path without following links");
        }
        struct stat metadata;
        if (fstat(next, &metadata) < 0) {
            close_private(next);
            return fail("inspect pinned host path component");
        }
        if (S_ISLNK(metadata.st_mode)) {
            close_private(next);
            return reject("host path leaf is a symbolic link");
        }
        bool require_protection = !leaf || protected_leaf;
        bool action_can_replace = metadata.st_uid == action_uid ||
            (metadata.st_mode & (S_IWGRP | S_IWOTH)) != 0;
        if (require_protection && action_can_replace) {
            close_private(next);
            return reject("host path component permits action, group, or other writes");
        }
        if (leaf)
            return next;
        parent = next;
        component = separator + 1;
    }
}

/**
 * Opens a pre-created target below the pinned root with no symlink ancestors.
 * Returns its descriptor and complete host name. All target parents must remain
 * host-owned and unwritable by the action for the complete mapping lifetime.
 */
static int open_root_target(struct namespace_supervisor *supervisor, const char *path,
    bool directory, char *host_path)
{
    if (path[0] != '/' || validate_output(path + 1) < 0)
        return reject("root target must be a normalized absolute non-root path");
    int length = snprintf(host_path, PATH_MAX, "%s%s", supervisor->root_path, path);
    if (length < 0 || length >= PATH_MAX)
        return reject("root target exceeds PATH_MAX");
    return open_host_path(host_path, supervisor->uid, directory, true);
}

/** Formats an already pinned descriptor as a kernel-resolved host proc path. */
static int descriptor_path(int descriptor, char *path, size_t capacity)
{
    int length = snprintf(path, capacity, "/proc/self/fd/%d", descriptor);
    if (length < 0 || (size_t) length >= capacity)
        return reject("descriptor path cannot be represented");
    return 0;
}

/**
 * Makes a bind mount immutable recursively. Directory runtimes reject device
 * access; the four explicit character-device mounts retain their device access.
 * Failure leaves the mount owned by this private namespace and fails launch.
 */
static int seal_mount(const char *path, bool devices)
{
    struct mount_attr attributes = {
        .attr_set = MOUNT_ATTR_RDONLY | MOUNT_ATTR_NOSUID
    };
    if (!devices)
        attributes.attr_set |= MOUNT_ATTR_NODEV;
    if (syscall(SYS_mount_setattr, AT_FDCWD, path, AT_RECURSIVE,
        &attributes, sizeof(attributes)) < 0)
        return fail("seal private bind mount recursively");
    return 0;
}

/**
 * Reads a host-owned regular manifest of at most MAX_FRAME_BYTES bytes. The
 * returned allocation is caller-owned, NUL-terminated, and preserves raw bytes.
 * A nonempty manifest must end in NUL; malformed input never reaches launch.
 */
static char *read_manifest(const char *path, uid_t action_uid, size_t *length)
{
    int descriptor = open_host_path(path, action_uid, false, true);
    if (descriptor < 0)
        return NULL;
    char pinned[64];
    if (descriptor_path(descriptor, pinned, sizeof(pinned)) < 0) {
        close_private(descriptor);
        return NULL;
    }
    int readable = open(pinned, O_RDONLY | O_CLOEXEC);
    if (close_private(descriptor) < 0) {
        if (readable >= 0)
            close_private(readable);
        return NULL;
    }
    if (readable < 0) {
        fail("open pinned manifest for reading");
        return NULL;
    }
    descriptor = readable;
    struct stat metadata;
    if (fstat(descriptor, &metadata) < 0 || !S_ISREG(metadata.st_mode) ||
        metadata.st_size < 0 || metadata.st_size > MAX_FRAME_BYTES) {
        close_private(descriptor);
        reject("manifest must be a regular file of at most 1048576 bytes");
        return NULL;
    }
    *length = (size_t) metadata.st_size;
    char *bytes = malloc(*length + 1);
    if (bytes == NULL) {
        close_private(descriptor);
        fail("allocate bounded host manifest");
        return NULL;
    }
    size_t received = 0;
    while (received < *length) {
        ssize_t count = read(descriptor, bytes + received, *length - received);
        if (count < 0 && errno == EINTR)
            continue;
        if (count <= 0) {
            close_private(descriptor);
            free(bytes);
            reject("manifest changed length or could not be read completely");
            return NULL;
        }
        received += (size_t) count;
    }
    char extra;
    ssize_t tail;
    do {
        tail = read(descriptor, &extra, 1);
    } while (tail < 0 && errno == EINTR);
    if (close_private(descriptor) < 0 || tail != 0 ||
        (*length > 0 && bytes[*length - 1] != '\0')) {
        free(bytes);
        reject("manifest changed length or lacks its final NUL");
        return NULL;
    }
    bytes[*length] = '\0';
    return bytes;
}

/**
 * Binds only the caller's declared runtime entries from pinned host objects to
 * exact pre-created targets. Only these target names are supported; a broad
 * home, /etc, worker directory, or credential bind cannot be requested.
 */
static int mount_runtime(struct namespace_supervisor *supervisor, char *manifest,
    size_t length)
{
    bool helper_bound = false;
    for (size_t offset = 0; offset < length;) {
        const char *source = manifest + offset;
        offset += strlen(source) + 1;
        if (offset >= length)
            return reject("runtime manifest has an unmatched source");
        const char *target = manifest + offset;
        offset += strlen(target) + 1;
        bool device = strcmp(target, "/dev/null") == 0 ||
            strcmp(target, "/dev/zero") == 0 || strcmp(target, "/dev/random") == 0 ||
            strcmp(target, "/dev/urandom") == 0;
        bool helper_image = strcmp(target, supervisor->image_path) == 0;
        if (target[0] != '/' || validate_output(target + 1) < 0 ||
            strcmp(target, "/proc") == 0 || strncmp(target, "/proc/", 6) == 0)
            return reject("runtime target must be a normalized absolute name outside proc");
        if (conflicts_private_temporary(target))
            return reject("runtime target conflicts with the private /tmp alias");
        for (size_t index = 0; index < supervisor->runtime_count; ++index)
            if (strcmp(target, supervisor->runtime_targets[index]) == 0)
                return reject("runtime manifest repeats a target");
        /* Named minimal devices are intentionally writable endpoints. Immutable
         * ancestors prevent replacement; exact character type/rdev validation
         * below is mandatory before binding the pinned object. */
        int source_fd = open_host_path(source, supervisor->uid, false, !device);
        if (source_fd < 0)
            return -1;
        struct stat metadata;
        if (fstat(source_fd, &metadata) < 0) {
            close_private(source_fd);
            return fail("inspect trusted runtime source");
        }
        bool directory = S_ISDIR(metadata.st_mode);
        if ((device && !S_ISCHR(metadata.st_mode)) ||
            (!directory && !device && !S_ISREG(metadata.st_mode))) {
            close_private(source_fd);
            return reject("runtime source type differs from its declared target kind");
        }
        if (device) {
            struct stat expected;
            if (stat(target, &expected) < 0 || metadata.st_rdev != expected.st_rdev) {
                close_private(source_fd);
                return reject("runtime character device differs from the named minimal device");
            }
        }
        if (helper_image) {
            if (helper_bound) {
                close_private(source_fd);
                return reject("runtime manifest repeats the trusted helper target");
            }
            struct stat executing;
            if (fstat(supervisor->image_fd, &executing) < 0 ||
                metadata.st_dev != executing.st_dev || metadata.st_ino != executing.st_ino) {
                close_private(source_fd);
                return reject("runtime waiter source differs from its pinned trusted image");
            }
        }
        char host_target[PATH_MAX];
        int target_fd = open_root_target(supervisor, target, directory, host_target);
        if (target_fd < 0) {
            close_private(source_fd);
            return -1;
        }
        char pinned_source[64];
        char pinned_target[64];
        int outcome = descriptor_path(source_fd, pinned_source, sizeof(pinned_source));
        if (outcome == 0)
            outcome = descriptor_path(target_fd, pinned_target, sizeof(pinned_target));
        if (outcome == 0 && mount(pinned_source, pinned_target, NULL,
            MS_BIND | MS_REC, NULL) < 0)
            outcome = fail("bind pinned runtime source");
        if (outcome == 0)
            outcome = seal_mount(host_target, device);
        if (close_private(source_fd) < 0)
            outcome = -1;
        if (close_private(target_fd) < 0)
            outcome = -1;
        if (outcome < 0)
            return -1;
        char *retained_target = strdup(target);
        if (retained_target == NULL)
            return fail("retain protected runtime target");
        supervisor->runtime_targets[supervisor->runtime_count++] = retained_target;
        if (helper_image)
            helper_bound = true;
    }
    return helper_bound ? 0 : reject("runtime manifest omits the trusted helper image");
}

/**
 * Requires a fresh upper, work, or temporary directory to contain only dot
 * entries. The descriptor pins the checked object through its later mount.
 */
static int require_empty_directory(int descriptor)
{
    int readable = openat(descriptor, ".", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (readable < 0)
        return fail("open fresh attempt directory for inspection");
    DIR *directory = fdopendir(readable);
    if (directory == NULL) {
        close_private(readable);
        return fail("inspect fresh attempt directory entries");
    }
    int outcome = 0;
    errno = 0;
    struct dirent *entry;
    while ((entry = readdir(directory)) != NULL) {
        if (strcmp(entry->d_name, ".") != 0 && strcmp(entry->d_name, "..") != 0) {
            outcome = reject("attempt upper, work, or temporary directory is not empty");
            break;
        }
    }
    if (entry == NULL && errno != 0)
        outcome = fail("read fresh attempt directory entries");
    if (closedir(directory) < 0)
        outcome = fail("close fresh attempt directory inspection");
    return outcome;
}

/**
 * Prepares the fixed empty root-owned /tmp placeholder before sealing the root.
 * A pre-existing nonempty subtree could contain original control/input/runtime
 * names, so hiding it with the compatibility alias is rejected before owner fork.
 * The action never receives this host placeholder's descriptor or write authority.
 */
static int prepare_temporary_alias(struct namespace_supervisor *supervisor)
{
    if (mkdirat(supervisor->root, "tmp", 0755) < 0 && errno != EEXIST)
        return fail("create private /tmp root placeholder");
    int descriptor = open_root_target(supervisor, PRIVATE_TMP_PATH, true,
        supervisor->temporary_alias_target);
    if (descriptor < 0)
        return -1;
    struct stat metadata;
    int outcome = 0;
    if (fstat(descriptor, &metadata) < 0)
        outcome = fail("inspect private /tmp root placeholder");
    else if (metadata.st_uid != 0 || !S_ISDIR(metadata.st_mode) ||
        (metadata.st_mode & (S_IWGRP | S_IWOTH)) != 0)
        outcome = reject("private /tmp placeholder must be an immutable root-owned directory");
    if (outcome == 0)
        outcome = require_empty_directory(descriptor);
    if (close_private(descriptor) < 0)
        outcome = -1;
    return outcome;
}

/**
 * Opens an exact host-chosen cgroup-v2 directory. Cgroup names may never be
 * selected by an action; Java retains matching containment ownership and births.
 */
static int open_cgroup(const char *path, uid_t action_uid)
{
    int descriptor = open_host_path(path, action_uid, true, true);
    if (descriptor < 0)
        return -1;
    struct statfs filesystem;
    if (fstatfs(descriptor, &filesystem) < 0 || filesystem.f_type != CGROUP2_SUPER_MAGIC) {
        close_private(descriptor);
        return reject("containment path is not a cgroup-v2 directory");
    }
    return descriptor;
}

/**
 * Requires the pinned attempt group's authoritative populated value to be zero.
 * Missing, duplicate, malformed, or unreadable state retains mount ownership.
 */
static int require_attempt_zero(int group)
{
    int descriptor = openat(group, "cgroup.events", O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
    if (descriptor < 0)
        return fail("open pinned attempt cgroup events");
    char bytes[4096];
    ssize_t length;
    do {
        length = read(descriptor, bytes, sizeof(bytes) - 1);
    } while (length < 0 && errno == EINTR);
    int outcome = close_private(descriptor);
    if (outcome < 0)
        return -1;
    if (length <= 0 || (size_t) length >= sizeof(bytes) - 1)
        return reject("attempt cgroup events cannot establish populated zero");
    bytes[length] = '\0';
    bool observed = false;
    for (char *line = bytes; *line;) {
        char *end = strchr(line, '\n');
        if (end == NULL)
            return reject("attempt cgroup events has an incomplete line");
        *end = '\0';
        if (strncmp(line, "populated ", 10) == 0) {
            if (observed || strcmp(line, "populated 0") != 0)
                return reject("attempt cgroup remains populated or has ambiguous state");
            observed = true;
        }
        line = end + 1;
    }
    return observed ? 0 : reject("attempt cgroup events has no populated state");
}

/**
 * Transfers one helper-pipe fragment with a 30-second started-frame deadline.
 * EOF, termination, and partial/uncertain writes are permanent protocol failures.
 */
static int helper_transfer(int descriptor, void *bytes, size_t length, bool writing,
    int64_t deadline)
{
    size_t done = 0;
    while (done < length) {
        int64_t now = monotonic_milliseconds();
        if (now < 0 || now >= deadline || termination_requested)
            return reject("helper frame transfer expired or was terminated");
        int64_t remaining = deadline - now;
        int timeout = remaining < CHILD_POLL_MS ? (int) remaining : CHILD_POLL_MS;
        struct pollfd ready = { .fd = descriptor, .events = writing ? POLLOUT : POLLIN };
        int result = poll(&ready, 1, timeout);
        if (result < 0 && errno == EINTR)
            continue;
        if (result < 0)
            return fail("poll private helper pipe");
        if (result == 0)
            continue;
        if ((ready.revents & ready.events) == 0)
            return reject("private helper pipe closed during frame");
        ssize_t count = writing ? write(descriptor, (char *) bytes + done, length - done) :
            read(descriptor, (char *) bytes + done, length - done);
        if (count < 0 && errno == EINTR)
            continue;
        if (count <= 0)
            return reject("private helper pipe transfer failed or reached EOF");
        done += (size_t) count;
    }
    return 0;
}

/** Sends one bounded helper receipt; failed writes are never replayed. */
static int helper_reply(const char *message)
{
    size_t length = strlen(message);
    if (length == 0 || length > MAX_FRAME_BYTES)
        return reject("helper receipt exceeds its frame bounds");
    unsigned char header[4] = {
        (unsigned char) (length >> 24), (unsigned char) (length >> 16),
        (unsigned char) (length >> 8), (unsigned char) length
    };
    int64_t now = monotonic_milliseconds();
    if (now < 0)
        return -1;
    int64_t deadline = now + OPERATION_TIMEOUT_MS;
    if (helper_transfer(STDOUT_FILENO, header, sizeof(header), true, deadline) < 0)
        return -1;
    return helper_transfer(STDOUT_FILENO, (void *) message, length, true, deadline);
}

/** Reads one started ASCII helper command into a caller-owned allocation. */
static char *helper_command(void)
{
    /* An idle owner may execute for arbitrarily long. Start the bounded frame
     * deadline only after the first command byte becomes ready. EOF and signals
     * still withdraw the namespace immediately. */
    struct pollfd incoming = { .fd = STDIN_FILENO, .events = POLLIN };
    for (;;) {
        if (termination_requested)
            return NULL;
        int ready = poll(&incoming, 1, CHILD_POLL_MS);
        if (ready < 0 && errno == EINTR)
            continue;
        if (ready < 0 || (ready > 0 && (incoming.revents & POLLIN) == 0)) {
            reject("private helper command pipe closed while idle");
            return NULL;
        }
        if (ready > 0)
            break;
    }
    int64_t now = monotonic_milliseconds();
    if (now < 0)
        return NULL;
    int64_t deadline = now + OPERATION_TIMEOUT_MS;
    unsigned char header[4];
    if (helper_transfer(STDIN_FILENO, header, sizeof(header), false, deadline) < 0)
        return NULL;
    uint32_t length = ((uint32_t) header[0] << 24) |
        ((uint32_t) header[1] << 16) | ((uint32_t) header[2] << 8) | header[3];
    if (length == 0 || length > MAX_FRAME_BYTES) {
        reject("helper command frame is outside its bounds");
        return NULL;
    }
    char *bytes = malloc((size_t) length + 1);
    if (bytes == NULL) {
        fail("allocate helper command frame");
        return NULL;
    }
    if (helper_transfer(STDIN_FILENO, bytes, length, false, deadline) < 0) {
        free(bytes);
        return NULL;
    }
    for (uint32_t index = 0; index < length; ++index) {
        unsigned char value = (unsigned char) bytes[index];
        if (value != '\t' && (value < 32 || value == 127)) {
            free(bytes);
            reject("helper command contains a path control byte");
            return NULL;
        }
    }
    bytes[length] = '\0';
    return bytes;
}

/** Indicates whether a writable mapping would hide a protected runtime path. */
static bool hides_runtime(const struct namespace_supervisor *supervisor, const char *path)
{
    const char *protected[] = { "/usr", "/bin", "/lib", "/lib64", "/etc", "/dev", "/proc" };
    for (size_t index = 0; index < sizeof(protected) / sizeof(protected[0]); ++index) {
        size_t length = strlen(protected[index]);
        if (strncmp(path, protected[index], length) == 0 &&
            (path[length] == '\0' || path[length] == '/'))
            return true;
    }
    for (size_t index = 0; index < supervisor->runtime_count; ++index) {
        const char *target = supervisor->runtime_targets[index];
        size_t path_length = strlen(path);
        size_t target_length = strlen(target);
        bool hides_target = strncmp(path, target, path_length) == 0 &&
            (target[path_length] == '\0' || target[path_length] == '/');
        bool inside_target = strncmp(path, target, target_length) == 0 &&
            (path[target_length] == '\0' || path[target_length] == '/');
        if (hides_target || inside_target)
            return true;
    }
    return false;
}

/** Indicates whether two absolute mount names are equal or nested. */
static bool nested_paths(const char *first, const char *second)
{
    size_t first_length = strlen(first);
    size_t second_length = strlen(second);
    return (strncmp(first, second, first_length) == 0 &&
        (second[first_length] == '\0' || second[first_length] == '/')) ||
        (strncmp(first, second, second_length) == 0 &&
        (first[second_length] == '\0' || first[second_length] == '/'));
}

/**
 * Installs one fresh attempt overlay and tmp bind at their original names,
 * plus a separate /tmp bind of the same fresh temporary source.
 * MAP fields are command, input target, tmp target, lower, upper, work, tmp
 * source, and attempt cgroup, separated by tabs. Any partial mount remains owned
 * until fail-closed teardown; a failed MAP never produces a MAPPED receipt.
 */
static int map_attempt(struct namespace_supervisor *supervisor, char *command)
{
    if (supervisor->input_mounted || supervisor->temporary_mounted ||
        supervisor->temporary_alias_mounted || supervisor->attempt_group >= 0)
        return reject("MAP requires a completely unmapped previous attempt");
    char *fields[8];
    fields[0] = command;
    int count = 1;
    for (char *cursor = command; *cursor; ++cursor) {
        if (*cursor == '\t') {
            if (count == 8 || cursor[1] == '\0' || cursor[1] == '\t')
                return reject("MAP has an empty or excessive field");
            *cursor = '\0';
            fields[count++] = cursor + 1;
        }
    }
    if (count != 8 || strcmp(fields[0], "MAP") != 0)
        return reject("MAP requires seven host-selected path fields");
    if (conflicts_private_temporary(fields[1]) || conflicts_private_temporary(fields[2]))
        return reject("original input or temporary target conflicts with the private /tmp alias");
    if (hides_runtime(supervisor, fields[1]) || hides_runtime(supervisor, fields[2]) ||
        nested_paths(fields[1], fields[2]))
        return reject("attempt mappings overlap each other or protected runtime");
    supervisor->attempt_group = open_cgroup(fields[7], supervisor->uid);
    if (supervisor->attempt_group < 0 || require_attempt_zero(supervisor->attempt_group) < 0)
        return -1;
    int target = open_root_target(supervisor, fields[1], true, supervisor->input_target);
    if (target < 0)
        return -1;
    int temporary = open_root_target(supervisor, fields[2], true,
        supervisor->temporary_target);
    if (temporary < 0) {
        close_private(target);
        return -1;
    }
    for (int index = 0; index < 4; ++index) {
        supervisor->sources[index] = open_host_path(fields[index + 3], supervisor->uid,
            true, false);
        if (supervisor->sources[index] < 0) {
            close_private(target);
            close_private(temporary);
            return -1;
        }
        if (index > 0 && require_empty_directory(supervisor->sources[index]) < 0) {
            close_private(target);
            close_private(temporary);
            return -1;
        }
        struct stat observed;
        if (fstat(supervisor->sources[index], &observed) < 0) {
            close_private(target);
            close_private(temporary);
            return fail("inspect pinned attempt source identity");
        }
        for (int previous = 0; previous < index; ++previous) {
            struct stat prior;
            if (fstat(supervisor->sources[previous], &prior) < 0 ||
                (observed.st_dev == prior.st_dev && observed.st_ino == prior.st_ino)) {
                close_private(target);
                close_private(temporary);
                return reject("attempt lower, upper, work, and temporary objects must be distinct");
            }
        }
    }
    int temporary_alias = open_root_target(supervisor, PRIVATE_TMP_PATH, true,
        supervisor->temporary_alias_target);
    if (temporary_alias < 0) {
        close_private(target);
        close_private(temporary);
        return -1;
    }
    char sources[4][64];
    char target_path[64];
    char temporary_path[64];
    char temporary_alias_path[64];
    int outcome = descriptor_path(target, target_path, sizeof(target_path));
    if (outcome == 0)
        outcome = descriptor_path(temporary, temporary_path, sizeof(temporary_path));
    if (outcome == 0)
        outcome = descriptor_path(temporary_alias, temporary_alias_path, sizeof(temporary_alias_path));
    for (int index = 0; outcome == 0 && index < 4; ++index)
        outcome = descriptor_path(supervisor->sources[index], sources[index], sizeof(sources[index]));
    char options[256];
    int length = snprintf(options, sizeof(options), "lowerdir=%s,upperdir=%s,workdir=%s",
        sources[0], sources[1], sources[2]);
    if (length < 0 || (size_t) length >= sizeof(options))
        outcome = reject("overlay mount options cannot be represented");
    if (outcome == 0) {
        if (mount("overlay", target_path, "overlay", MS_NOSUID | MS_NODEV, options) < 0)
            outcome = fail("mount isolated attempt overlay");
        else
            supervisor->input_mounted = true;
    }
    if (outcome == 0) {
        if (mount(sources[3], temporary_path, NULL, MS_BIND, NULL) < 0)
            outcome = fail("bind isolated attempt temporary directory");
        else
            supervisor->temporary_mounted = true;
    }
    if (outcome == 0 && mount(NULL, supervisor->temporary_target, NULL,
        MS_BIND | MS_REMOUNT | MS_NOSUID | MS_NODEV, NULL) < 0)
        outcome = fail("restrict isolated temporary mount");
    if (outcome == 0) {
        if (mount(sources[3], temporary_alias_path, NULL, MS_BIND, NULL) < 0)
            outcome = fail("bind private /tmp alias of isolated attempt temporary directory");
        else
            supervisor->temporary_alias_mounted = true;
    }
    if (outcome == 0 && mount(NULL, supervisor->temporary_alias_target, NULL,
        MS_BIND | MS_REMOUNT | MS_NOSUID | MS_NODEV, NULL) < 0)
        outcome = fail("restrict private /tmp alias mount");
    if (close_private(temporary_alias) < 0)
        outcome = -1;
    if (close_private(target) < 0)
        outcome = -1;
    if (close_private(temporary) < 0)
        outcome = -1;
    return outcome;
}

/**
 * Unmaps only after authoritative attempt zero. Ordinary unmount deliberately
 * rejects busy mounts; lazy detach would lose the ownership proof. On failure,
 * remaining flags/descriptors retain the exact unresolved cleanup authority.
 */
static int unmap_attempt(struct namespace_supervisor *supervisor)
{
    if (supervisor->attempt_group < 0)
        return reject("UNMAP requires a pinned attempt group");
    if (require_attempt_zero(supervisor->attempt_group) < 0)
        return -1;
    if (supervisor->temporary_alias_mounted) {
        if (umount2(supervisor->temporary_alias_target, 0) < 0)
            return fail("unmount zero attempt private /tmp alias");
        supervisor->temporary_alias_mounted = false;
    }
    if (supervisor->temporary_mounted) {
        if (umount2(supervisor->temporary_target, 0) < 0)
            return fail("unmount zero attempt temporary directory");
        supervisor->temporary_mounted = false;
    }
    if (supervisor->input_mounted) {
        if (umount2(supervisor->input_target, 0) < 0)
            return fail("unmount zero attempt overlay");
        supervisor->input_mounted = false;
    }
    for (int index = 0; index < 4; ++index) {
        int source = supervisor->sources[index];
        supervisor->sources[index] = -1;
        if (source >= 0 && close_private(source) < 0)
            return -1;
    }
    int group = supervisor->attempt_group;
    supervisor->attempt_group = -1;
    return close_private(group);
}

/**
 * Collects the authentic owner status, optionally killing it first on failure.
 * PID-namespace init death kills its namespace descendants, but the separate
 * pinned cgroup zero check is still required before mappings may be released.
 */
static int collect_namespace_owner(struct namespace_supervisor *supervisor, bool terminate)
{
    if (supervisor->collected)
        return 0;
    if (terminate && kill(supervisor->owner, SIGKILL) < 0 && errno != ESRCH)
        return fail("kill retained namespace owner");
    int64_t now = monotonic_milliseconds();
    if (now < 0)
        return -1;
    int64_t deadline = now + OPERATION_TIMEOUT_MS;
    for (;;) {
        int status;
        pid_t observed = waitpid(supervisor->owner, &status, WNOHANG);
        if (observed == supervisor->owner) {
            if (WIFEXITED(status))
                supervisor->owner_status = WEXITSTATUS(status);
            else if (WIFSIGNALED(status))
                supervisor->owner_status = 128 + WTERMSIG(status);
            else
                return reject("namespace owner has unsupported wait status");
            supervisor->collected = true;
            return 0;
        }
        if (observed < 0 && errno != EINTR)
            return fail("collect retained namespace owner");
        int64_t current = monotonic_milliseconds();
        if (current < 0 || current >= deadline)
            return reject("namespace owner remains live; retain native cleanup lease");
        struct timespec pause = { .tv_sec = 0, .tv_nsec = CHILD_POLL_MS * 1000000L };
        if (nanosleep(&pause, NULL) < 0 && errno != EINTR)
            return fail("pause namespace owner collection");
    }
}

/**
 * Executes only the independently pinned glibc waiter after privilege drop, with
 * an empty loader environment and unchanged original argv tail. No payload execution is
 * available here. File descriptors other than stdio/self close before self-exec.
 */
static void launch_namespace_owner(struct namespace_supervisor *supervisor,
    int argc, char **argv, int image, char **environment, int output, int error)
{
    struct pollfd parent = { .fd = supervisor->parent_identity, .events = POLLIN };
    if (prctl(PR_SET_PDEATHSIG, (unsigned long) SIGKILL, 0UL, 0UL, 0UL) < 0 ||
        poll(&parent, 1, 0) != 0)
        { fail("pin supervisor parent liveness"); _exit(125); }
    int membership = openat(supervisor->owner_group, "cgroup.procs",
        O_WRONLY | O_NOFOLLOW | O_CLOEXEC);
    if (membership < 0 || write(membership, "0\n", 2) != 2 || close(membership) < 0)
        { fail("enter exact owner cgroup"); _exit(125); }
    if (fchdir(supervisor->root) < 0 || chroot(".") < 0 || chdir("/") < 0)
        { fail("enter private action root"); _exit(125); }
    if (mount("proc", "/proc", "proc", MS_RDONLY | MS_NOSUID | MS_NODEV | MS_NOEXEC, NULL) < 0)
        { fail("mount private owner proc"); _exit(125); }
    int input = open("/dev/null", O_RDONLY | O_CLOEXEC);
    if (input < 0 || dup2(input, STDIN_FILENO) < 0 || dup2(output, STDOUT_FILENO) < 0 ||
        dup2(error, STDERR_FILENO) < 0)
        { fail("open and install private standard streams"); _exit(125); }
    if (setgroups(0, NULL) < 0 || setresgid(supervisor->gid, supervisor->gid, supervisor->gid) < 0 ||
        setresuid(supervisor->uid, supervisor->uid, supervisor->uid) < 0 ||
        prctl(PR_SET_NO_NEW_PRIVS, 1UL, 0UL, 0UL, 0UL) < 0)
        { fail("drop action credentials and privileges"); _exit(125); }
    struct __user_cap_header_struct header = { .version = _LINUX_CAPABILITY_VERSION_3, .pid = 0 };
    struct __user_cap_data_struct capabilities[2] = { { 0 }, { 0 } };
    if (syscall(SYS_capset, &header, capabilities) < 0)
        { fail("clear action capabilities"); _exit(125); }
    /* Credential changes clear PDEATHSIG; reinstall it after the final drop. */
    if (prctl(PR_SET_PDEATHSIG, (unsigned long) SIGKILL, 0UL, 0UL, 0UL) < 0 ||
        poll(&parent, 1, 0) != 0)
        { fail("reinstall owner parent-death protection"); _exit(125); }
    char **owner_arguments = calloc((size_t) argc + 1, sizeof(char *));
    if (owner_arguments == NULL)
        { fail("allocate original owner arguments"); _exit(125); }
    int index = 0;
    owner_arguments[index++] = argv[7];
    owner_arguments[index++] = argv[8];
    owner_arguments[index++] = argv[11];
    owner_arguments[index++] = "--socket-identity";
    owner_arguments[index++] = argv[9];
    owner_arguments[index++] = argv[10];
    owner_arguments[index++] = "--cwd";
    owner_arguments[index++] = argv[12];
    owner_arguments[index++] = "0";
    for (int original = 16; original < argc; ++original)
        owner_arguments[index++] = argv[original];
    if (require_unprivileged_owner() < 0)
        { fail("verify unprivileged original owner"); _exit(125); }
    struct sigaction broken_pipe = { .sa_handler = SIG_DFL };
    sigemptyset(&broken_pipe.sa_mask);
    if (sigaction(SIGPIPE, &broken_pipe, NULL) < 0)
        { fail("restore original broken-pipe disposition"); _exit(125); }
    /* The pinned waiter image is the only inherited non-stdio descriptor.
     * close_range CLOEXEC preserves its availability for fexecve while ensuring
     * no host directory, cgroup, manifest, or namespace handle reaches payloads. */
    if (syscall(SYS_close_range, 3U, ~0U, CLOSE_RANGE_CLOEXEC) < 0)
        { fail("close inherited native authority descriptors"); _exit(125); }
    fexecve(image, owner_arguments, environment);
    fail("exec pinned unprivileged waiter");
    _exit(125);
}

/**
 * Acknowledges a failed setup only before any namespace owner was forked.
 * The exact pinned owner group must be independently empty. Private mounts die
 * with this original supervisor after its CLOSE exchange and successful exit;
 * this acknowledgment is never an action result or an uncertain MAP recovery.
 */
static int acknowledge_setup_failure(struct namespace_supervisor *supervisor)
{
    if (supervisor->owner != 0 || supervisor->owner_group < 0 ||
        require_attempt_zero(supervisor->owner_group) < 0 || helper_reply("SETUP_FAILED") < 0)
        return 125;
    char *command = helper_command();
    if (command == NULL)
        return 125;
    bool close_requested = strcmp(command, "CLOSE") == 0;
    free(command);
    if (!close_requested || require_attempt_zero(supervisor->owner_group) < 0)
        return 125;
    if ((supervisor->root >= 0 && close_private(supervisor->root) < 0) ||
        close_private(supervisor->owner_group) < 0 || helper_reply("CLOSED 0") < 0)
        return 125;
    return 0;
}

/**
 * Launches a statically linked trusted supervisor, then services private MAP,
 * UNMAP, and CLOSE commands. The file header defines the exact caller contract.
 * Returns zero only after CLOSED and verified native
 * cleanup, or 125 with unresolved ownership reported on stderr.
 */
static int run_supervisor(int argc, char **argv)
{
    if (argc < 18 || strcmp(argv[16], "--") != 0 || getuid() != 0 || geteuid() != 0 ||
        getauxval(AT_BASE) != 0) {
        reject("--launch requires a trusted static root image and its complete launch arguments");
        return 125;
    }
    /* No namespace mutation or child fork can race host-side birth/image/cgroup
     * authentication. The host alone supplies INITIALIZE over owned pipes. */
    char *initialization = helper_command();
    if (initialization == NULL)
        return 125;
    bool initialized = strcmp(initialization, "INITIALIZE") == 0;
    free(initialization);
    if (!initialized || helper_reply("INITIALIZED") < 0)
        return 125;
    struct namespace_supervisor supervisor = {
        .root = -1, .owner_group = -1, .attempt_group = -1, .parent_identity = -1, .image_fd = -1,
        .sources = { -1, -1, -1, -1 }
    };
    uint64_t uid;
    uint64_t gid;
    uint64_t socket_device;
    uint64_t socket_inode;
    if (parse_decimal(argv[2], UINT32_MAX - 1, &uid) < 0 || uid == 0 ||
        parse_decimal(argv[3], UINT32_MAX - 1, &gid) < 0 || gid == 0 ||
        parse_decimal(argv[9], UINT64_MAX, &socket_device) < 0 ||
        parse_decimal(argv[10], UINT64_MAX, &socket_inode) < 0 || socket_inode == 0 ||
        !valid_capability(argv[11]) || argv[12][0] != '/' ||
        validate_output(argv[12] + 1) < 0)
        return 125;
    if (conflicts_private_temporary(argv[7]) || conflicts_private_temporary(argv[8]) ||
        conflicts_private_temporary(argv[12]) || conflicts_private_temporary(argv[17])) {
        reject("original helper, control, cwd, or executable target conflicts with the private /tmp alias");
        return 125;
    }
    supervisor.uid = (uid_t) uid;
    supervisor.gid = (gid_t) gid;
    supervisor.root_path = argv[5];
    supervisor.image_path = argv[7];
    supervisor.root = open_host_path(argv[5], supervisor.uid, true, true);
    supervisor.owner_group = open_cgroup(argv[4], supervisor.uid);
    if (supervisor.root < 0 || supervisor.owner_group < 0)
        return 125;
    size_t runtime_length;
    size_t environment_length;
    char *runtime = read_manifest(argv[6], supervisor.uid, &runtime_length);
    char *environment_bytes = read_manifest(argv[13], supervisor.uid, &environment_length);
    if (runtime == NULL || environment_bytes == NULL)
        return acknowledge_setup_failure(&supervisor);
    supervisor.runtime_targets = calloc((runtime_length / 2) + 1, sizeof(char *));
    if (supervisor.runtime_targets == NULL)
        return acknowledge_setup_failure(&supervisor);
    char **environment = calloc(environment_length + 1, sizeof(char *));
    if (environment == NULL)
        return acknowledge_setup_failure(&supervisor);
    size_t entry_count = 0;
    for (size_t offset = 0; offset < environment_length;) {
        char *entry = environment_bytes + offset;
        char *equals = strchr(entry, '=');
        if (equals == NULL || equals == entry) {
            reject("environment manifest entries require a nonempty name followed by equals");
            return acknowledge_setup_failure(&supervisor);
        }
        environment[entry_count++] = entry;
        offset += strlen(entry) + 1;
    }
    if (entry_count != 0) {
        reject("production waiter requires an empty trusted loader environment");
        return acknowledge_setup_failure(&supervisor);
    }
    int output = open_host_path(argv[14], supervisor.uid, false, true);
    int error = open_host_path(argv[15], supervisor.uid, false, true);
    if (output < 0 || error < 0)
        return acknowledge_setup_failure(&supervisor);
    /* The path opener validates identity and ancestors read-only. Reopen these
     * exact pinned regular files for writing without pathname re-resolution. */
    int diagnostics[2] = { output, error };
    for (int index = 0; index < 2; ++index) {
        struct stat metadata;
        char pinned[64];
        if (fstat(diagnostics[index], &metadata) < 0 || !S_ISREG(metadata.st_mode) ||
            descriptor_path(diagnostics[index], pinned, sizeof(pinned)) < 0)
            return acknowledge_setup_failure(&supervisor);
        int writable = open(pinned, O_WRONLY | O_APPEND | O_CLOEXEC);
        if (writable < 0 || close_private(diagnostics[index]) < 0)
            return acknowledge_setup_failure(&supervisor);
        diagnostics[index] = writable;
    }
    output = diagnostics[0];
    error = diagnostics[1];
    int image = open_host_path(argv[7], supervisor.uid, false, true);
    supervisor.image_fd = image;
    if (image < 0 || unshare(CLONE_NEWNS | CLONE_NEWPID) < 0 ||
        mount(NULL, "/", NULL, MS_REC | MS_PRIVATE, NULL) < 0)
    {
        fail("establish trusted supervisor PID/mount namespaces");
        return acknowledge_setup_failure(&supervisor);
    }
    /* A descriptor opened before unshare refers to the original namespace's
     * mount tree. Reopen the immutable name in this namespace, independently
     * matching the pinned inode before using it as either bind endpoint. */
    int current_root = open_host_path(supervisor.root_path, supervisor.uid, true, true);
    struct stat pinned_identity;
    struct stat current_identity;
    if (current_root < 0 || fstat(supervisor.root, &pinned_identity) < 0 ||
        fstat(current_root, &current_identity) < 0 ||
        pinned_identity.st_dev != current_identity.st_dev ||
        pinned_identity.st_ino != current_identity.st_ino || close_private(supervisor.root) < 0) {
        fail("reopen authenticated root in the private mount namespace");
        return acknowledge_setup_failure(&supervisor);
    }
    supervisor.root = current_root;
    char pinned_root[64];
    if (descriptor_path(supervisor.root, pinned_root, sizeof(pinned_root)) < 0 ||
        mount(pinned_root, supervisor.root_path, NULL, MS_BIND, NULL) < 0) {
        fail("bind authenticated supervisor root at its immutable host name");
        return acknowledge_setup_failure(&supervisor);
    }
    /* Reopen through the immutable host name to acquire the newly attached mount,
     * rather than retaining a descriptor into the pre-bind mount underneath it. */
    int mounted_root = open_host_path(supervisor.root_path, supervisor.uid, true, true);
    struct stat original_root;
    struct stat rebound_root;
    if (mounted_root < 0 || fstat(supervisor.root, &original_root) < 0 ||
        fstat(mounted_root, &rebound_root) < 0 || original_root.st_dev != rebound_root.st_dev ||
        original_root.st_ino != rebound_root.st_ino || close_private(supervisor.root) < 0) {
        fail("revalidate and reopen bound supervisor root");
        return acknowledge_setup_failure(&supervisor);
    }
    supervisor.root = mounted_root;
    if (mount_runtime(&supervisor, runtime, runtime_length) < 0) {
        fail("mount authenticated supervisor runtime entries");
        return acknowledge_setup_failure(&supervisor);
    }
    if (prepare_temporary_alias(&supervisor) < 0) {
        fail("prepare authenticated supervisor private temporary alias");
        return acknowledge_setup_failure(&supervisor);
    }
    /* The host pins an action-owned connectable socket below immutable host-owned
     * ancestors. Leaf write permission cannot replace a name in that directory;
     * retain the exact descriptor/type/device/inode checks before binding. */
    int endpoint = open_host_path(argv[8], supervisor.uid, false, false);
    char endpoint_target[PATH_MAX];
    int placeholder = open_root_target(&supervisor, argv[8], false, endpoint_target);
    struct stat socket_metadata;
    char pinned_endpoint[64];
    char pinned_placeholder[64];
    if (endpoint < 0 || placeholder < 0 || fstat(endpoint, &socket_metadata) < 0 ||
        !S_ISSOCK(socket_metadata.st_mode) || (uint64_t) socket_metadata.st_dev != socket_device ||
        (uint64_t) socket_metadata.st_ino != socket_inode ||
        descriptor_path(endpoint, pinned_endpoint, sizeof(pinned_endpoint)) < 0 ||
        descriptor_path(placeholder, pinned_placeholder, sizeof(pinned_placeholder)) < 0 ||
        mount(pinned_endpoint, pinned_placeholder, NULL, MS_BIND, NULL) < 0 ||
        seal_mount(endpoint_target, false) < 0 ||
        mount(NULL, argv[5], NULL, MS_BIND | MS_REMOUNT | MS_RDONLY | MS_NOSUID | MS_NODEV, NULL) < 0)
    {
        fail("pin and seal authenticated socket and supervisor root");
        return acknowledge_setup_failure(&supervisor);
    }
    struct sigaction terminate = { .sa_handler = request_termination };
    struct sigaction broken_pipe = { .sa_handler = SIG_IGN };
    struct sigaction child_status = { .sa_handler = SIG_DFL };
    sigemptyset(&terminate.sa_mask);
    sigemptyset(&broken_pipe.sa_mask);
    sigemptyset(&child_status.sa_mask);
    if (sigaction(SIGINT, &terminate, NULL) < 0 || sigaction(SIGTERM, &terminate, NULL) < 0 ||
        sigaction(SIGHUP, &terminate, NULL) < 0 || sigaction(SIGPIPE, &broken_pipe, NULL) < 0 ||
        sigaction(SIGCHLD, &child_status, NULL) < 0)
        return acknowledge_setup_failure(&supervisor);
    supervisor.parent_identity = (int) syscall(SYS_pidfd_open, getpid(), 0U);
    if (supervisor.parent_identity < 0)
        return acknowledge_setup_failure(&supervisor);
    supervisor.owner = fork();
    if (supervisor.owner < 0)
        return 125;
    if (supervisor.owner == 0)
        launch_namespace_owner(&supervisor, argc, argv, image, environment, output, error);
    free(environment);
    free(environment_bytes);
    free(runtime);
    int launch_descriptors[] = { image, output, error, endpoint, placeholder, supervisor.parent_identity };
    for (size_t index = 0; index < sizeof(launch_descriptors) / sizeof(launch_descriptors[0]); ++index)
        if (close_private(launch_descriptors[index]) < 0)
            goto failure;
    uint64_t birth;
    if (read_birth(supervisor.owner, &birth) < 0)
        goto failure;
    char receipt[128];
    int receipt_length = snprintf(receipt, sizeof(receipt), "LAUNCHED %ld %" PRIu64,
        (long) supervisor.owner, birth);
    if (receipt_length < 0 || (size_t) receipt_length >= sizeof(receipt) || helper_reply(receipt) < 0)
        goto failure;
    for (;;) {
        struct pollfd control = { .fd = STDIN_FILENO, .events = POLLIN };
        int ready = poll(&control, 1, CHILD_POLL_MS);
        if (termination_requested)
            goto failure;
        if (ready < 0 && errno == EINTR)
            continue;
        if (ready < 0 || (control.revents & (POLLERR | POLLHUP | POLLNVAL)) != 0)
            goto failure;
        if (ready == 0)
            continue;
        char *command = helper_command();
        if (command == NULL)
            goto failure;
        int outcome;
        if (strncmp(command, "MAP\t", 4) == 0) {
            outcome = map_attempt(&supervisor, command);
            if (outcome == 0)
                outcome = helper_reply("MAPPED");
        } else if (strcmp(command, "UNMAP") == 0) {
            outcome = unmap_attempt(&supervisor);
            if (outcome == 0)
                outcome = helper_reply("UNMAPPED");
        } else if (strcmp(command, "CLOSE") == 0) {
            free(command);
            if (supervisor.attempt_group >= 0 || supervisor.input_mounted ||
                supervisor.temporary_mounted || supervisor.temporary_alias_mounted ||
                collect_namespace_owner(&supervisor, false) < 0 ||
                require_attempt_zero(supervisor.owner_group) < 0)
                goto failure;
            int length = snprintf(receipt, sizeof(receipt), "CLOSED %d", supervisor.owner_status);
            if (length < 0 || (size_t) length >= sizeof(receipt) || helper_reply(receipt) < 0)
                goto failure;
            if (close_private(supervisor.root) < 0 || close_private(supervisor.owner_group) < 0)
                return 125;
            return 0;
        } else {
            outcome = reject("helper command must be MAP, UNMAP, or CLOSE");
        }
        free(command);
        if (outcome < 0)
            goto failure;
    }

failure:
    if (collect_namespace_owner(&supervisor, true) < 0 ||
        (supervisor.attempt_group >= 0 && unmap_attempt(&supervisor) < 0))
        reject("native supervisor cleanup remains uncertain; retain host cleanup lease");
    return 125;
}

/**
 * Selects the host-only namespace launch or the unprivileged action owner. The
 * launcher never executes an action; authenticated Frida replacement is the
 * only path from the owner's prepared child to the original payload image.
 */
int main(int argc, char **argv)
{
    if (argc > 1 && strcmp(argv[1], "--launch") == 0)
        return run_supervisor(argc, argv);
    return run_owner(argc, argv);
}
