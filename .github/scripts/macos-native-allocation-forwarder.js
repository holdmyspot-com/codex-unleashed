/* Forward one owned worker's mmap without entering JavaScript in the hook.
 * Keep this script and its CModule alive until the worker exits normally. */
const originalMapping = Memory.alloc(Process.pointerSize);
const workerThread = Memory.alloc(8);
const matchingCalls = Memory.alloc(4);
matchingCalls.writeU32(0);
const nativeForwarder = new CModule(`
#include <stddef.h>

typedef void * (* Map)(void *, size_t, int, int, int, long long);
extern Map original_mapping;
extern unsigned long long worker_thread;
extern unsigned int matching_calls;
extern int read_thread_id(void *, unsigned long long *);
extern int * errno_location(void);

void * forward_mapping(void * address, size_t bytes, int protection,
    int flags, int descriptor, long long offset)
{
    const int is_probe_request = (address == NULL) && (bytes == 268435456) &&
        (protection == 3) && (flags == 0x1002) && (descriptor == -1) && (offset == 0);
    if (is_probe_request) {
        int * error = errno_location();
        const int saved_error = *error;
        unsigned long long thread_id = 0;
        const int thread_result = read_thread_id(NULL, &thread_id);
        if ((thread_result == 0) && (thread_id == worker_thread))
            matching_calls++;
        *error = saved_error;
    }
    return original_mapping(address, bytes, protection, flags, descriptor, offset);
}
`, { original_mapping: originalMapping, worker_thread: workerThread,
  matching_calls: matchingCalls,
  read_thread_id: Module.getGlobalExportByName('pthread_threadid_np'),
  errno_location: Module.getGlobalExportByName('__error') });

rpc.exports = {
  /* The controller passes the worker's independently resolved native address. */
  install(address, threadId) {
    workerThread.writeU64(uint64(threadId));
    const target = ptr(address);
    originalMapping.writePointer(Interceptor.replaceFast(target, nativeForwarder.forward_mapping));
    Interceptor.flush();
    return { address: target.toString(), architecture: Process.arch };
  },
  /* Read only after the worker has returned from its single allocation call. */
  matchingCalls() {
    return matchingCalls.readU32();
  }
};
send({ event: 'candidate_ready', pid: Process.id });
