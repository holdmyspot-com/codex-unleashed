/* Hold null heap allocation/reallocation results from non-excluded compiler
 * threads while the controller raises its job allowance. Threads classified
 * as instrumentation still call the original API but skip retry waits. */
/* Trampoline for resizing an existing heap block; a null result retains it. */
const originalHeapReallocation = Memory.alloc(Process.pointerSize);
const originalHeapAllocation = Memory.alloc(Process.pointerSize);
/* Denied, permission, returned, release, and first-denial selection events. */
const allocationEvents = Memory.alloc(Process.pointerSize * 5);
/* Excluded thread count followed by up to 64 instrumentation thread IDs. */
const excludedThreads = Memory.alloc(4 * 65);
/* Thread, flags, attempts, first/final error, permission/release waits, signal;
 * followed by requested bytes and returned pointer as 64-bit values, then a
 * 32-bit operation ID: 1 for allocation, 2 for reallocation. */
const allocationState = Memory.alloc(64);
const kernel = Process.getModuleByName('kernel32.dll');
const nativeRetry = new CModule(`
#include <stddef.h>
typedef void * (* Allocate)(void *, unsigned int, size_t);
extern Allocate original_heap_allocation;
typedef void * (* Reallocate)(void *, unsigned int, void *, size_t);
extern Reallocate original_heap_reallocation;
extern unsigned int operation_id;
extern void * allocation_events[5];
extern unsigned int excluded_threads[65];
extern unsigned int allocation_state[8];
extern size_t requested_bytes;
extern void * returned_pointer;
extern unsigned int current_thread_id(void);
extern unsigned int get_last_error(void);
extern void set_last_error(unsigned int);
extern unsigned int wait_event(void *, unsigned int);
extern int signal_event(void *);

/* Invoke allocation for operation 1, otherwise reallocation, with unchanged
 * native arguments. A null reallocation result leaves the old block owned by
 * its caller; this dispatcher neither frees blocks nor changes error state. */
void * call_original(void * heap, unsigned int flags, void * old, size_t bytes,
                     unsigned int operation)
{
    if (operation == 1)
        return original_heap_allocation(heap, flags, bytes);
    return original_heap_reallocation(heap, flags, old, bytes);
}

/* Call the original operation with unchanged arguments and incoming last-error.
 * Null results from non-excluded threads without flag 4 wait up to 30000 ms
 * for permission; a signaled permission event permits one retry. The first
 * such denial selected by event 4 publishes diagnostics and signals event 0,
 * then signals event 2 after its result is recorded and waits up to 30000 ms
 * for release event 3. Other eligible denials also wait/retry but do not
 * overwrite that record. Return the last attempt's pointer and last-error;
 * no block is freed here, including an old block after failed reallocation. */
void * retry_allocation(void * heap, unsigned int flags, void * old, size_t bytes,
                        unsigned int operation)
{
    const unsigned int incoming_error = get_last_error();
    const unsigned int thread = current_thread_id();
    int is_excluded_thread = 0;
    for (unsigned int index = 0; index < excluded_threads[0]; index++) {
        if (thread == excluded_threads[index + 1])
            is_excluded_thread = 1;
    }
    set_last_error(incoming_error);
    void * result = call_original(heap, flags, old, bytes, operation);
    unsigned int final_error = get_last_error();
    const int may_wait = (result == NULL) && (is_excluded_thread == 0) &&
        ((flags & 4) == 0);
    if (may_wait) {
        const int is_first_denial = (wait_event(allocation_events[4], 0) == 0);
        if (is_first_denial) {
            operation_id = operation;
            allocation_state[0] = thread;
            allocation_state[1] = flags;
            allocation_state[2] = 1;
            allocation_state[3] = final_error;
            requested_bytes = bytes;
            allocation_state[7] = signal_event(allocation_events[0]);
        }
        const unsigned int permission = wait_event(allocation_events[1], 30000);
        if (is_first_denial)
            allocation_state[5] = permission;
        if (permission == 0) {
            set_last_error(incoming_error);
            if (is_first_denial)
                allocation_state[2]++;
            result = call_original(heap, flags, old, bytes, operation);
            final_error = get_last_error();
        }
        if (is_first_denial) {
            returned_pointer = result;
            allocation_state[4] = final_error;
            signal_event(allocation_events[2]);
            allocation_state[6] = wait_event(allocation_events[3], 30000);
        }
    }
    set_last_error(final_error);
    return result;
}

/* Retry an RtlAllocateHeap request through the shared denial/wait path;
 * preserve its native arguments, returned pointer and final last-error. */
void * retry_heap(void * heap, unsigned int flags, size_t bytes)
{
    return retry_allocation(heap, flags, NULL, bytes, 1);
}

/* Retry an RtlReAllocateHeap request through the shared denial/wait path.
 * Preserve old on null failure; return the last attempt's pointer/last-error. */
void * retry_reallocate(void * heap, unsigned int flags, void * old, size_t bytes)
{
    return retry_allocation(heap, flags, old, bytes, 2);
}
`, { original_heap_reallocation: originalHeapReallocation, operation_id: allocationState.add(48),
  original_heap_allocation: originalHeapAllocation, allocation_events: allocationEvents,
  excluded_threads: excludedThreads, allocation_state: allocationState,
  requested_bytes: allocationState.add(32), returned_pointer: allocationState.add(40),
  current_thread_id: kernel.getExportByName('GetCurrentThreadId'),
  get_last_error: kernel.getExportByName('GetLastError'),
  set_last_error: kernel.getExportByName('SetLastError'),
  wait_event: kernel.getExportByName('WaitForSingleObject'),
  signal_event: kernel.getExportByName('SetEvent') });

rpc.exports = {
  /* Install both heap hooks. initialThreads contains pre-attachment compiler
   * thread IDs; handles contains exactly five duplicated native event handles
   * in denied/permission/returned/release/first-denial order. Return the state
   * address, excluded thread IDs, target and native architecture. Refuse more
   * than 64 instrumentation threads. This classification is diagnostic only.
   * Install once before compiler stdin is admitted. Event 0 reports the selected
   * denial; event 1 permits retry; event 2 publishes its final result; event 3
   * releases that call; event 4 selects the first denial. The controller supplies
   * the event states and keeps these worker-owned handles and the script alive
   * while hooks can run. state_address is diagnostic memory, not an allocation
   * returned to the compiler; installing hooks does not close the event handles. */
  install(initialThreads, handles) {
    const initial = new Set(initialThreads);
    const excluded = Process.enumerateThreads().filter(thread => initial.has(thread.id) === false)
      .map(thread => thread.id);
    if (excluded.length > 64)
      throw new Error('More instrumentation threads than the owned exclusion buffer supports');
    excludedThreads.writeU32(excluded.length);
    excluded.forEach((thread, index) => excludedThreads.add(4 * (index + 1)).writeU32(thread));
    handles.forEach((handle, index) => allocationEvents.add(Process.pointerSize * index).writePointer(ptr(handle)));
    allocationState.writeByteArray(new Uint8Array(64));
    allocationState.add(20).writeU32(0xffffffff);
    allocationState.add(24).writeU32(0xffffffff);
    const target = Process.getModuleByName('ntdll.dll').getExportByName('RtlAllocateHeap');
    originalHeapAllocation.writePointer(Interceptor.replaceFast(target, nativeRetry.retry_heap));
    const reallocTarget = Process.getModuleByName('ntdll.dll').getExportByName('RtlReAllocateHeap');
    originalHeapReallocation.writePointer(Interceptor.replaceFast(reallocTarget, nativeRetry.retry_reallocate));
    Interceptor.flush();
    return { state_address: allocationState.toString(), excluded_threads: excluded,
      target: target.toString(), architecture: Process.arch };
  }
};
send({ event: 'candidate_ready', pid: Process.id });
