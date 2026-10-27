/* Diagnostic control: forward VirtualAlloc through a native replacement, with
 * no JavaScript callback on the allocation path. The controller owns the
 * injected session and keeps this script loaded until the worker exits. */
const allocationAddress = Process.getModuleByName('kernel32.dll').getExportByName('VirtualAlloc');

/* Frida's replacement context routes a call back to the target to its original
 * implementation. Keep the module alive for the installed replacement. */
const nativeForwarder = new CModule(`
#include <stddef.h>

extern void * original_allocate(void * address, size_t bytes,
    unsigned int type, unsigned int protection);

/* Return the original allocator's result for the unchanged request. */
void * forward_allocate(void * address, size_t bytes,
    unsigned int type, unsigned int protection)
{
    return original_allocate(address, bytes, type, protection);
}
`, { original_allocate: allocationAddress });

Interceptor.replace(allocationAddress, nativeForwarder.forward_allocate);
send({ event: 'hook_installed', pid: Process.id, address: allocationAddress.toString() });
