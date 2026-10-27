/* Diagnostic control: forward VirtualAlloc through Frida's direct replacement,
 * using its returned trampoline to call the original implementation. The
 * controller keeps the script and native module alive until worker exit. */
const allocationAddress = Process.getModuleByName('kernel32.dll').getExportByName('VirtualAlloc');

/* Retain native entry instructions before installing the direct replacement,
 * so a rejected ARM64 entry can be compared with Frida's relocation rules. */
const allocationModule = Process.findModuleByAddress(allocationAddress);
const entryInstructions = [];
const entryBytes = Array.from(new Uint8Array(allocationAddress.readByteArray(32)))
  .map(value => value.toString(16).padStart(2, '0')).join('');
let instructionAddress = allocationAddress;
let entryDecodeError = null;
for (let index = 0; index < 8; index++) {
  let instruction;
  try {
    instruction = Instruction.parse(instructionAddress);
  } catch (error) {
    entryDecodeError = { address: instructionAddress.toString(), message: String(error) };
    break;
  }
  entryInstructions.push({ address: instructionAddress.toString(), mnemonic: instruction.mnemonic,
    operands: instruction.opStr });
  instructionAddress = instruction.next;
  const isFunctionEnd = ['ret', 'br', 'b', 'jmp'].includes(instruction.mnemonic);
  if (isFunctionEnd) {
    break;
  }
}
send({ event: 'hook_target', pid: Process.id, module: allocationModule.name,
  offset: allocationAddress.sub(allocationModule.base).toString(), bytes: entryBytes,
  instructions: entryInstructions, decode_error: entryDecodeError });

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
