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
send({ event: 'hook_target', pid: Process.id, architecture: Process.arch, entries: branchEntries,
  chain_truncated: branchEntries[branchEntries.length - 1].branch_target !== null,
  kernelbase_entry: describeEntry(kernelBaseAddress) });

/* The original function pointer is supplied before pending hook installation
 * is flushed by send(), while the owned worker awaits allocation permission. */
const originalAllocation = Memory.alloc(Process.pointerSize);
const nativeForwarder = new CModule(`
#include <stddef.h>

typedef void * (* Allocate)(void *, size_t, unsigned int, unsigned int);
extern Allocate original_allocation;

/* Call the original trampoline without entering JavaScript or another hook. */
void * forward_allocate(void * address, size_t bytes,
    unsigned int type, unsigned int protection)
{
    return original_allocation(address, bytes, type, protection);
}
`, { original_allocation: originalAllocation });

originalAllocation.writePointer(Interceptor.replaceFast(allocationAddress, nativeForwarder.forward_allocate));
send({ event: 'hook_installed', pid: Process.id, address: allocationAddress.toString() });
