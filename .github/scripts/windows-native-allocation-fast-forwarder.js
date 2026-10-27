/* Diagnostic control: forward VirtualAlloc through Frida's direct replacement,
 * using its returned trampoline to call the original implementation. The
 * controller keeps the script and native module alive until worker exit. */
const allocationAddress = Process.getModuleByName('kernel32.dll').getExportByName('VirtualAlloc');

/* Describe up to eight entry instructions and 32 raw bytes at address. Return
 * a relative ARM64 entry-branch target using Frida's exact Int64 immediate;
 * retain decoding failures without skipping the hook-installation check. */
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
const nativeForwarder = new CModule(`
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

/* Call the original trampoline without entering JavaScript or another hook. */
void * forward_allocate(void * address, size_t bytes,
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
  /* Return the number of matching native calls observed before worker cleanup. */
  probeAllocationCount() {
    return probeAllocationCount.readU32();
  },
  /* Configure the single request before the worker receives start permission. */
  armNative(threadId, blockedHandle, retryHandle, milliseconds) {
    blockedEvent.writePointer(ptr(blockedHandle));
    retryEvent.writePointer(ptr(retryHandle));
    retryState.writeU32(threadId);
    retryState.add(28).writeU32(milliseconds);
    retryState.add(24).writeU32(0xffffffff);
    retryState.add(4).writeU32(1);
    return true;
  },
  /* Snapshot only at a published blocked event or after caller return. */
  nativeRetryState() {
    return { matching_calls: retryState.add(8).readU32(), attempts: retryState.add(12).readU32(),
      first_error: retryState.add(16).readU32(), final_error: retryState.add(20).readU32(),
      wait_result: retryState.add(24).readU32(), signal_ok: retryState.add(32).readU32(),
      error_sentinel: retryState.add(36).readU32(), pointer: returnedPointer.readPointer().toString() };
  }
};

originalAllocation.writePointer(Interceptor.replaceFast(replacementAddress, nativeForwarder.forward_allocate));
send({ event: 'hook_installed', pid: Process.id, address: replacementAddress.toString() });
