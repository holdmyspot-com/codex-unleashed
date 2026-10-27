/* Diagnostic control: forward VirtualAlloc through Frida's direct replacement,
 * using its returned trampoline to call the original implementation. The
 * controller keeps the script and native module alive until worker exit. */
const allocationAddress = Process.getModuleByName('kernel32.dll').getExportByName('VirtualAlloc');

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
