/* Hold a matching owned-worker mmap denial in native code until permission
 * or timeout. The worker lowers its soft address limit after installation;
 * this hook attempts to restore the captured limit before retrying or returning
 * the original error. Keep the script loaded until worker exit. */
const originalMapping = Memory.alloc(Process.pointerSize);
const workerThread = Memory.alloc(8);
/* Original soft/hard RLIMIT_AS, captured before the worker lowers its limit. */
const originalLimits = Memory.alloc(16);
/* Resource number, blocked notification fd, retry permission fd, wait milliseconds. */
const retryConfiguration = Memory.alloc(16);
/* Matching calls, original attempts, first errno, poll result, notification bytes,
 * permission bytes, limit-restore result, diagnostic clobbered errno. */
const retryState = Memory.alloc(32);
const returnedPointer = Memory.alloc(Process.pointerSize);
const nativeRetry = new CModule(`
#include <stddef.h>

typedef void * (* Map)(void *, size_t, int, int, int, long long);
struct ResourceLimit { unsigned long long soft; unsigned long long hard; };
struct PollDescriptor { int descriptor; short events; short returned_events; };
extern Map original_mapping;
extern unsigned long long worker_thread;
extern struct ResourceLimit original_limits;
extern int retry_configuration[4];
extern volatile int retry_state[8];
extern void * returned_pointer;
extern int read_thread_id(void *, unsigned long long *);
extern int * errno_location(void);
extern long write_pipe(int, const void *, size_t);
extern long read_pipe(int, void *, size_t);
extern int poll_pipe(struct PollDescriptor *, unsigned int, int);
extern int restore_limit(int, const struct ResourceLimit *);

/* Select the configured thread's mmap(NULL, 268435456, 3, 0x1002, -1, 0).
 * Forward other requests unchanged. If the selected request returns
 * MAP_FAILED/ENOMEM, write B to the notification pipe, poll permission for the
 * configured milliseconds, then attempt to restore the captured address limit.
 * Retry once only after reading R and restoring the limit successfully.
 * Otherwise return the original failure with its errno restored; a retry
 * returns its own pointer and errno. No allocation is freed by this hook. */
void * retry_mapping(void * address, size_t bytes, int protection,
    int flags, int descriptor, long long offset)
{
    int * error = errno_location();
    const int incoming_error = *error;
    unsigned long long thread_id = 0;
    const int thread_result = read_thread_id(NULL, &thread_id);
    const int is_probe_request = (address == NULL) && (bytes == 268435456) &&
        (protection == 3) && (flags == 0x1002) && (descriptor == -1) &&
        (offset == 0) && (thread_result == 0) && (thread_id == worker_thread);
    *error = incoming_error;
    if (!is_probe_request)
        return original_mapping(address, bytes, protection, flags, descriptor, offset);

    retry_state[0]++;
    retry_state[1]++;
    void * result = original_mapping(address, bytes, protection, flags, descriptor, offset);
    returned_pointer = result;
    const int is_memory_denial = (result == (void *) -1) && (*error == 12);
    if (!is_memory_denial)
        return result;

    const int allocation_error = *error;
    retry_state[2] = allocation_error;
    const char blocked = 'B';
    retry_state[4] = (int) write_pipe(retry_configuration[1], &blocked, 1);
    struct PollDescriptor permission = { retry_configuration[2], 1, 0 };
    retry_state[3] = poll_pipe(&permission, 1, retry_configuration[3]);
    char command = 0;
    const int has_readable_permission = (retry_state[3] == 1) &&
        ((permission.returned_events & 1) == 1);
    if (has_readable_permission)
        retry_state[5] = (int) read_pipe(retry_configuration[2], &command, 1);

    retry_state[6] = restore_limit(retry_configuration[0], &original_limits);
    const int can_retry = (retry_state[5] == 1) && (command == 'R') &&
        (retry_state[6] == 0);
    if (can_retry) {
        retry_state[1]++;
        result = original_mapping(address, bytes, protection, flags, descriptor, offset);
        returned_pointer = result;
        return result;
    }

    /* Deliberately clobber errno so the negative case proves restoration. */
    *error = 4660;
    retry_state[7] = *error;
    *error = allocation_error;
    return result;
}
`, { original_mapping: originalMapping, worker_thread: workerThread,
  original_limits: originalLimits, retry_configuration: retryConfiguration,
  retry_state: retryState, returned_pointer: returnedPointer,
  read_thread_id: Module.getGlobalExportByName('pthread_threadid_np'),
  errno_location: Module.getGlobalExportByName('__error'),
  write_pipe: Module.getGlobalExportByName('write'),
  read_pipe: Module.getGlobalExportByName('read'),
  poll_pipe: Module.getGlobalExportByName('poll'),
  restore_limit: Module.getGlobalExportByName('setrlimit') });

rpc.exports = {
  /* Install once at the worker's mmap address before its limit changes.
   * threadId identifies its pthread thread; addressResource is RLIMIT_AS.
   * blockedDescriptor writes B and retryDescriptor reads R through inherited
   * pipes. waitMilliseconds sets the permission poll timeout (the controller
   * uses 1000 for timeout and 30000 for retry). Capture the original limits,
   * reset diagnostics, and return target address/architecture. Throw if
   * getrlimit fails. Keep descriptors open and script loaded until worker exit. */
  install(address, threadId, addressResource, blockedDescriptor, retryDescriptor, waitMilliseconds) {
    workerThread.writeU64(uint64(threadId));
    const getLimits = new NativeFunction(Module.getGlobalExportByName('getrlimit'), 'int', ['int', 'pointer']);
    if (getLimits(addressResource, originalLimits) !== 0)
      throw new Error('Unable to capture the worker address-space limits');
    [addressResource, blockedDescriptor, retryDescriptor, waitMilliseconds].forEach((value, index) => {
      retryConfiguration.add(index * 4).writeS32(value);
    });
    for (let index = 0; index < 8; index++)
      retryState.add(index * 4).writeS32(0);
    retryState.add(12).writeS32(-2);
    retryState.add(24).writeS32(-2);
    const target = ptr(address);
    originalMapping.writePointer(Interceptor.replaceFast(target, nativeRetry.retry_mapping));
    Interceptor.flush();
    return { address: target.toString(), architecture: Process.arch };
  },
  /* Return matching entries/attempts, first errno, poll/read/write/restore
   * results, the deliberate errno sentinel, and the last original mmap pointer.
   * Read after B or caller return: poll_result and restore_result start at -2;
   * byte counts and first_errno start at zero, and later fields may still be
   * pending after B. Reading does not resume the worker or own the pointer. */
  state() {
    return { matching_calls: retryState.readS32(), attempts: retryState.add(4).readS32(),
      first_errno: retryState.add(8).readS32(), poll_result: retryState.add(12).readS32(),
      notification_bytes: retryState.add(16).readS32(), permission_bytes: retryState.add(20).readS32(),
      restore_result: retryState.add(24).readS32(), clobbered_errno: retryState.add(28).readS32(),
      pointer: returnedPointer.readPointer().toString() };
  }
};
send({ event: 'candidate_ready', pid: Process.id });
