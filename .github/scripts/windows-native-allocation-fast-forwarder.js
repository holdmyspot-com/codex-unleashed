/* Forward VirtualAlloc through a native hook, with an optional one-request
 * denial wait and retry configured by armNative(). Without arming, every request
 * is forwarded unchanged. The controller keeps the script and native module
 * alive until worker exit. */
const allocationAddress = Process.getModuleByName('kernel32.dll').getExportByName('VirtualAlloc');

/* Read 32 bytes at a module-backed entry address and decode up to eight
 * instructions, stopping at ret/br/b/jmp or a decode failure. Return the
 * module/offset, bytes, instructions, decode error and first-instruction ARM64
 * b target as an absolute hexadecimal address (null when absent). Instruction
 * parse failures become decode_error; byte reads and module lookup are not
 * caught here and can abort setup. */
function describeEntry(address) {
  const module = Process.findModuleByAddress(address);
  const instructions = [];
  const bytes = Array.from(new Uint8Array(address.readByteArray(32)))
    .map(value => value.toString(16).padStart(2, '0')).join('');
  let instructionAddress = address;
  let decodeError = null;
  let branchTarget = null;
  for (let index = 0; index < 8; index++) {
    let instruction;
    try {
      instruction = Instruction.parse(instructionAddress);
    } catch (error) {
      decodeError = { address: instructionAddress.toString(), message: String(error) };
      break;
    }
    instructions.push({ address: instructionAddress.toString(), mnemonic: instruction.mnemonic,
      operands: instruction.opStr });
    instructionAddress = instruction.next;
    const hasEntryRedirect = (index === 0) && (instruction.mnemonic === 'b');
    if (hasEntryRedirect) {
      const operand = instruction.operands[0];
      if (operand.type === 'imm') {
        branchTarget = '0x' + operand.value.toString(16);
      } else {
        decodeError = { address: instruction.address.toString(), message: 'Entry branch operand is not an immediate target' };
      }
    }
    const isFunctionEnd = ['ret', 'br', 'b', 'jmp'].includes(instruction.mnemonic);
    if (isFunctionEnd) {
      break;
    }
  }

  return { address: address.toString(), module: module.name, offset: address.sub(module.base).toString(),
    bytes, instructions, decode_error: decodeError, branch_target: branchTarget };
}

/* Frida follows relative branch thunks before selecting the patch site. Inspect
 * up to four entries, retaining the final target and marking truncation if
 * that bound leaves another entry to inspect. */
const branchEntries = [];
let branchAddress = allocationAddress;
for (let index = 0; index < 4; index++) {
  const entry = describeEntry(branchAddress);
  branchEntries.push(entry);
  if (entry.branch_target === null) {
    break;
  }
  branchAddress = ptr(entry.branch_target);
}
const kernelBaseAddress = Process.getModuleByName('kernelbase.dll').getExportByName('VirtualAlloc');
/* Windows ARM64's Kernel32 entry resolves to a 12-byte indirect thunk, which
 * cannot fit Frida's 16-byte fast redirect. Test the implementation export
 * instead; the native counter below proves that the caller reaches it. */
const replacementAddress = Process.arch === 'arm64' ? kernelBaseAddress : allocationAddress;
send({ event: 'hook_target', pid: Process.id, architecture: Process.arch, entries: branchEntries,
  chain_truncated: branchEntries[branchEntries.length - 1].branch_target !== null,
  kernelbase_entry: describeEntry(kernelBaseAddress), replacement_address: replacementAddress.toString() });

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

typedef void * (* Allocate)(void *, size_t, unsigned int, unsigned int);
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

/* Forward VirtualAlloc calls unchanged unless armed for this thread's request:
 * NULL address, 268435456 bytes, type 0x3000 and protection 0x04. Disarm on the first match.
 * After a null result, signal blocked_event and, only if signaling succeeds,
 * wait up to the configured milliseconds for retry_event. Retry once only on
 * a signaled wait. Return the last attempt's pointer and last-error; diagnostic
 * calls and the timeout sentinel must not change that error. No block is freed. */
void * retry_allocation(void * address, size_t bytes,
    unsigned int type, unsigned int protection)
{
    const unsigned int incoming_error = get_last_error();
    const int is_probe_allocation = (bytes == 268435456) &&
        (type == 0x3000) && (protection == 0x04);
    if (is_probe_allocation)
        probe_allocation_count++;
    const int is_armed_request = is_probe_allocation && (address == NULL) &&
        (retry_state[1] == 1) && (current_thread_id() == retry_state[0]);
    set_last_error(incoming_error);
    if (!is_armed_request)
        return original_allocation(address, bytes, type, protection);

    retry_state[1] = 0;
    retry_state[2]++;
    retry_state[3]++;
    void * result = original_allocation(address, bytes, type, protection);
    unsigned int final_error = get_last_error();
    retry_state[4] = final_error;
    if (result == NULL) {
        retry_state[8] = signal_event(blocked_event);
        if (retry_state[8] != 0) {
            retry_state[6] = wait_for_event(retry_event, retry_state[7]);
            if (retry_state[6] == 0) {
                set_last_error(incoming_error);
                retry_state[3]++;
                result = original_allocation(address, bytes, type, protection);
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
  /* Return hook entries since script load with 268435456 bytes, type 0x3000 and
   * protection 0x04, across all threads and address hints. Retries through the
   * original trampoline are excluded. Read before worker cleanup. */
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
