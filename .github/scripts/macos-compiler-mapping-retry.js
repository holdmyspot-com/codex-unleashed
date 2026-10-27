/* Hold a real main-thread compiler mmap denial after its original call returns.
 * Native pipe records avoid JavaScript allocation while RLIMIT_AS is active. */
const originalMapping = Memory.alloc(Process.pointerSize);
/* Original and reduced soft/hard RLIMIT_AS pairs, each two unsigned 64-bit words. */
const addressLimits = Memory.alloc(32);
/* Resource number, notification writer and permission reader inherited by rustc. */
const retryConfiguration = Memory.alloc(12);
/* Indicates whether the main thread has applied its reduced address limit. */
const limitApplied = Memory.alloc(4);
const nativeRetry = new CModule(`
#include <stddef.h>
typedef void * (* Map)(void *, size_t, int, int, int, long long);
struct ResourceLimit { unsigned long long soft; unsigned long long hard; };
struct PollDescriptor { int descriptor; short events; short returned_events; };
extern Map original_mapping;
extern struct ResourceLimit address_limits[2];
extern int retry_configuration[3];
extern int limit_applied;
extern int is_main_thread(void);
extern int read_thread_id(void *, unsigned long long *);
extern int * errno_location(void);
extern long write_pipe(int, const void *, size_t);
extern long read_pipe(int, void *, size_t);
extern int poll_pipe(struct PollDescriptor *, unsigned int, int);
extern int set_limit(int, const struct ResourceLimit *);
extern unsigned int task_port;
extern int inspect_task(unsigned int, unsigned int, void *, unsigned int *);

/* Send one 80-byte pipe record; the controller rejects missing/partial records.
 * Words are phase, thread, bytes, attempts, errno, pointer, limited/original soft
 * allowances, restoration result and permission-poll result, all signed 64-bit.
 * Phase 3 uses bytes for current virtual size and errno for task_info status. */
void report(long long phase, unsigned long long thread, size_t bytes,
            long long attempts, int error, void * result, int restored, int polled)
{
    long long record[10] = { phase, (long long) thread, (long long) bytes, attempts,
        error, (long long) result, (long long) address_limits[1].soft,
        (long long) address_limits[0].soft, restored, polled };
    write_pipe(retry_configuration[1], record, sizeof(record));
}

/* Wait at most 30 seconds for one permission byte; return its poll result. */
int await_permission(char expected)
{
    struct PollDescriptor permission = { retry_configuration[2], 1, 0 };
    const int polled = poll_pipe(&permission, 1, 30000);
    char command = 0;
    const int has_permission = (polled == 1) &&
        ((permission.returned_events & 1) == 1) &&
        (read_pipe(retry_configuration[2], &command, 1) == 1) && (command == expected);
    if (has_permission)
        return 1;
    return polled == 0 ? 0 : -1;
}

void * retry_mapping(void * address, size_t bytes, int protection,
                     int flags, int descriptor, long long offset)
{
    int * error = errno_location();
    const int incoming_error = *error;
    const int is_compiler_main_thread = (is_main_thread() != 0);
    *error = incoming_error;
    if (is_compiler_main_thread == 0)
        return original_mapping(address, bytes, protection, flags, descriptor, offset);

    unsigned long long thread = 0;
    read_thread_id(NULL, &thread);
    if (limit_applied == 0) {
        unsigned long long basic_info[6] = {0};
        unsigned int count = 12;
        const int inspected = inspect_task(task_port, 20, basic_info, &count);
        if (inspected != 0) {
            report(3, thread, basic_info[0], 0, inspected, NULL, -2, -2);
            *error = incoming_error;
            return original_mapping(address, bytes, protection, flags, descriptor, offset);
        }
        /* stdin processing can grow the compiler after the controller measures it.
         * Sample here so the kernel accepts the limit before this mapping call. */
        address_limits[1].soft = basic_info[0] + 4096;
        report(3, thread, basic_info[0], 0, inspected, NULL, -2, -2);
        const int installed = set_limit(retry_configuration[0], &address_limits[1]);
        if (installed != 0) {
            report(0, thread, bytes, 0, *error, (void *) -1, installed, -2);
            *error = incoming_error;
            return original_mapping(address, bytes, protection, flags, descriptor, offset);
        }
        limit_applied = 1;
    }
    *error = incoming_error;
    void * result = original_mapping(address, bytes, protection, flags, descriptor, offset);
    int final_error = *error;
    const int is_memory_denial = (result == (void *) -1) && (final_error == 12);
    if (is_memory_denial == 0)
        return result;

    report(1, thread, bytes, 1, final_error, result, -2, -2);
    const int permission = await_permission('R');
    const int restored = set_limit(retry_configuration[0], &address_limits[0]);
    int attempts = 1;
    const int can_retry = (permission == 1) && (restored == 0);
    if (can_retry) {
        attempts++;
        *error = incoming_error;
        result = original_mapping(address, bytes, protection, flags, descriptor, offset);
        final_error = *error;
    }
    report(2, thread, bytes, attempts, final_error, result, restored, permission);
    await_permission('F');
    *error = final_error;
    return result;
}
`, { original_mapping: originalMapping, address_limits: addressLimits,
  retry_configuration: retryConfiguration, limit_applied: limitApplied,
  is_main_thread: Module.getGlobalExportByName('pthread_main_np'),
  read_thread_id: Module.getGlobalExportByName('pthread_threadid_np'),
  errno_location: Module.getGlobalExportByName('__error'),
  write_pipe: Module.getGlobalExportByName('write'),
  read_pipe: Module.getGlobalExportByName('read'),
  poll_pipe: Module.getGlobalExportByName('poll'),
  set_limit: Module.getGlobalExportByName('setrlimit'),
  task_port: Module.getGlobalExportByName('mach_task_self_'),
  inspect_task: Module.getGlobalExportByName('task_info') });

rpc.exports = {
  /* Arm the mmap hook with a resource ID and two inherited pipe endpoints.
   * Return original limits, target and architecture. The first main-thread
   * mapping samples current virtual size and adds 4096 bytes for its limit.
   * Only main-thread mappings change limits or wait. */
  install(resource, notification, permission) {
    const mainThread = new NativeFunction(Module.getGlobalExportByName('pthread_main_np'), 'int', []);
    if (mainThread() !== 0)
      throw new Error('The instrumentation script unexpectedly runs on the main thread');
    const getLimits = new NativeFunction(Module.getGlobalExportByName('getrlimit'), 'int', ['int', 'pointer']);
    if (getLimits(resource, addressLimits) !== 0)
      throw new Error('Unable to capture the compiler address-space limits');
    addressLimits.add(16).writeU64(0);
    addressLimits.add(24).writeU64(addressLimits.add(8).readU64());
    [resource, notification, permission].forEach((value, index) => retryConfiguration.add(index * 4).writeS32(value));
    limitApplied.writeS32(0);
    const target = Module.getGlobalExportByName('mmap');
    originalMapping.writePointer(Interceptor.replaceFast(target, nativeRetry.retry_mapping));
    Interceptor.flush();
    return { target: target.toString(), architecture: Process.arch,
      original_soft: addressLimits.readU64().toString(),
      original_hard: addressLimits.add(8).readU64().toString(), allowance_bytes: 4096 };
  }
};
send({ event: 'candidate_ready', pid: Process.id });
