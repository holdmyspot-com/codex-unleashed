/* Observe allocator entries during an owned compiler's stdin compilation.
 * Each distinct hooked address reports its first call after arm(); aliases at
 * that address share one report. The observer does not replace allocator results
 * or change resource limits, and has no controller-controlled wait. */
const allocationNames = new Set(['malloc', 'calloc', 'realloc', 'aligned_alloc',
  'posix_memalign', 'mmap', 'VirtualAlloc', 'HeapAlloc', 'RtlAllocateHeap']);
/* Keep listeners alive until the owned compiler terminates. */
const allocationListeners = [];

rpc.exports = {
  /* Attach first-call listeners to distinct addresses exported under the names
   * in allocationNames. Return installed/rejected name-module-address records
   * and the architecture; rejected records include the attachment error.
   * Calls emit compiler_allocation_observed; RtlAllocateHeap also reports the
   * heap, flags, requested byte count and caller backtrace. Listeners remain
   * active until the compiler exits; the controller calls arm() once. */
  arm() {
    const addresses = new Set();
    const installed = [];
    const rejected = [];
    for (const module of Process.enumerateModules()) {
      for (const entry of module.enumerateExports()) {
        if (entry.type === 'function' && allocationNames.has(entry.name)) {
          const address = entry.address.toString();
          if (addresses.has(address))
            continue;
          addresses.add(address);
          const identity = { name: entry.name, module: module.name, address: address };
          let observed = false;
          try {
            const listener = Interceptor.attach(entry.address, {
              onEnter(args) {
                if (observed)
                  return;
                observed = true;
                const request = {};
                if (entry.name === 'RtlAllocateHeap') {
                  request.heap = args[0].toString();
                  request.flags = args[1].toUInt32();
                  request.requested_bytes_hex = args[2].toString();
                  request.callers = Thread.backtrace(this.context, Backtracer.ACCURATE)
                    .map(address => DebugSymbol.fromAddress(address).toString());
                }
                send({ event: 'compiler_allocation_observed', pid: Process.id,
                  thread_id: this.threadId, ...identity, ...request });
              }
            });
            allocationListeners.push(listener);
            installed.push(identity);
          } catch (error) {
            rejected.push({ ...identity, error: String(error) });
          }
        }
      }
    }
    Interceptor.flush();
    return { installed: installed, rejected: rejected, architecture: Process.arch };
  }
};
send({ event: 'candidate_ready', pid: Process.id });
