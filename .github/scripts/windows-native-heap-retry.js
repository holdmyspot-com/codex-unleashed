/* Test one owned worker's denied flags-zero RtlAllocateHeap call.
 * The native replacement waits only after the original API returns null;
 * it retries internally after the controller permits it, preserving error state. */
const replacementAddress = Process.getModuleByName('ntdll.dll').getExportByName('RtlAllocateHeap');
send({ event: 'hook_target', pid: Process.id, architecture: Process.arch,
  replacement_address: replacementAddress.toString() });

/* The original function pointer is supplied before pending hook installation
 * is flushed by send(), while the owned worker awaits allocation permission. */
const originalAllocation = Memory.alloc(Process.pointerSize);
/* Count only requests matching the owned worker's single 256 MiB allocation. */
const probeAllocationCount = Memory.alloc(4);
probeAllocationCount.writeU32(0);
/* One armed thread owns this diagnostic state. Events publish completed writes
 * to the controller; the controller reads again only after the call returns. */
const retryState = Memory.alloc(40);
retryState.writeByteArray(new Uint8Array(40));
const blockedEvent = Memory.alloc(Process.pointerSize);
const retryEvent = Memory.alloc(Process.pointerSize);
const returnedPointer = Memory.alloc(Process.pointerSize);
returnedPointer.writePointer(NULL);
const kernel = Process.getModuleByName('kernel32.dll');
const nativeRetry = new CModule(`
#include <stddef.h>

typedef void * (* Allocate)(void *, unsigned int, size_t);
extern Allocate original_allocation;
extern unsigned int probe_allocation_count;
extern unsigned int retry_state[10];
extern void * blocked_event;
extern void * retry_event;
extern void * returned_pointer;
extern unsigned int current_thread_id(void);
extern unsigned int get_last_error(void);
extern void set_last_error(unsigned int error);
extern int signal_event(void * event);
extern unsigned int wait_for_event(void * event, unsigned int milliseconds);

/* Forward RtlAllocateHeap calls unchanged unless armed for this thread's request:
 * non-null heap, flags 0 and 268435456 bytes. Disarm on the first match.
 * After a null result, signal blocked_event and, only if signaling succeeds,
 * wait up to the configured milliseconds for retry_event. Retry once only on
 * a signaled wait. Return the last attempt's pointer and last-error; diagnostic
 * calls and the timeout sentinel must not change that error. No block is freed. */
void * retry_allocation(void * heap, unsigned int flags, size_t bytes)
{
    const unsigned int incoming_error = get_last_error();
    const int is_probe_allocation = (bytes == 268435456) && (flags == 0);
    if (is_probe_allocation)
        probe_allocation_count++;
    const int is_armed_request = is_probe_allocation && (heap != NULL) &&
        (retry_state[1] == 1) && (current_thread_id() == retry_state[0]);
    set_last_error(incoming_error);
    if (!is_armed_request)
        return original_allocation(heap, flags, bytes);

    retry_state[1] = 0;
    retry_state[2]++;
    retry_state[3]++;
    void * result = original_allocation(heap, flags, bytes);
    unsigned int final_error = get_last_error();
    retry_state[4] = final_error;
    if (result == NULL) {
        retry_state[8] = signal_event(blocked_event);
        if (retry_state[8] != 0) {
            retry_state[6] = wait_for_event(retry_event, retry_state[7]);
            if (retry_state[6] == 0) {
                set_last_error(incoming_error);
                retry_state[3]++;
                result = original_allocation(heap, flags, bytes);
                final_error = get_last_error();
            } else {
                /* Deliberately clobber error state so the timeout fixture
                 * detects omission of the restoration below. */
                retry_state[9] = 0x20001234;
                set_last_error(retry_state[9]);
            }
        }
    }
    returned_pointer = result;
    retry_state[5] = final_error;
    set_last_error(final_error);
    return result;
}
`, { original_allocation: originalAllocation, probe_allocation_count: probeAllocationCount,
  retry_state: retryState, blocked_event: blockedEvent, retry_event: retryEvent,
  returned_pointer: returnedPointer,
  current_thread_id: kernel.getExportByName('GetCurrentThreadId'),
  get_last_error: kernel.getExportByName('GetLastError'),
  set_last_error: kernel.getExportByName('SetLastError'),
  signal_event: kernel.getExportByName('SetEvent'),
  wait_for_event: kernel.getExportByName('WaitForSingleObject') });

rpc.exports = {
  /* Return hook entries since script load with flags 0 and 268435456 bytes,
   * across all threads and heap arguments. Retries through the original
   * trampoline are excluded. Read before worker cleanup. */
  probeAllocationCount() {
    return probeAllocationCount.readU32();
  },
  /* Arm the next matching request on threadId before starting the worker.
   * blockedHandle and retryHandle are event handles duplicated into the worker;
   * the hook signals blockedHandle on null and waits milliseconds on retryHandle.
   * Return true after storing configuration. The controller arms once and keeps
   * the handles and script alive until worker exit; this does not reset counts. */
  armNative(threadId, blockedHandle, retryHandle, milliseconds) {
    blockedEvent.writePointer(ptr(blockedHandle));
    retryEvent.writePointer(ptr(retryHandle));
    retryState.writeU32(threadId);
    retryState.add(28).writeU32(milliseconds);
    retryState.add(24).writeU32(0xffffffff);
    retryState.add(4).writeU32(1);
    return true;
  },
  /* Return matching entries, original-call attempts, first/final last-error,
   * wait result, signal result, deliberate error sentinel and returned pointer.
   * Read after blocked-event publication or caller return. While blocked,
   * wait_result remains 0xffffffff and final_error/pointer may still be pending.
   * This read neither resumes the worker nor transfers pointer ownership. */
  nativeRetryState() {
    return { matching_calls: retryState.add(8).readU32(), attempts: retryState.add(12).readU32(),
      first_error: retryState.add(16).readU32(), final_error: retryState.add(20).readU32(),
      wait_result: retryState.add(24).readU32(), signal_ok: retryState.add(32).readU32(),
      error_sentinel: retryState.add(36).readU32(), pointer: returnedPointer.readPointer().toString() };
  }
};

originalAllocation.writePointer(Interceptor.replaceFast(replacementAddress, nativeRetry.retry_allocation));
send({ event: 'hook_installed', pid: Process.id, address: replacementAddress.toString() });
