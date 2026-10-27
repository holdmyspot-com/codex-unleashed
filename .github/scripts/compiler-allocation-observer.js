/* Observe allocator entries during an owned compiler's stdin compilation.
 * Each export reports only its first call. This observer changes no results,
 * resource limits or allocation timing policy and does not accept blocking. */
const allocationNames = new Set(['malloc', 'calloc', 'realloc', 'aligned_alloc',
  'posix_memalign', 'mmap', 'VirtualAlloc', 'HeapAlloc', 'RtlAllocateHeap']);
/* Keep listeners alive until the owned compiler terminates. */
const allocationListeners = [];

rpc.exports = {
  /* Install listeners on unique exported addresses; retain individual failures.
   * Returns the installed entries and rejected entries for coverage assessment. */
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
              onEnter() {
                if (observed)
                  return;
                observed = true;
                send({ event: 'compiler_allocation_observed', pid: Process.id,
                  thread_id: this.threadId, ...identity });
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
