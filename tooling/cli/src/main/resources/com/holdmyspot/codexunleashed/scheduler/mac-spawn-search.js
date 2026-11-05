/* Keeps a gated image's PATH-searched spawns resumable.
 *
 * Frida's child gating adds POSIX_SPAWN_START_SUSPENDED to the caller's attribute object inside each posix_spawn call
 * and never removes it. posix_spawnp tries posix_spawn once per PATH entry with that same object, so after the first
 * miss the next attempt already carries the flag. Frida then records that the caller asked for a suspended child and its
 * resume leaves the child parked at its spawn breakpoint, as Cargo's `git` child was. Restoring the caller's own flags
 * after every attempt lets Frida see them unchanged on the next one. */
{
  const spawnSearchGetFlags = new NativeFunction(Module.getGlobalExportByName('posix_spawnattr_getflags'), 'int',
    ['pointer', 'pointer']);
  const spawnSearchSetFlags = new NativeFunction(Module.getGlobalExportByName('posix_spawnattr_setflags'), 'int',
    ['pointer', 'int']);
  // The caller's attribute object and flags for each thread's posix_spawnp call in progress.
  const spawnSearches = new Map();
  const restoreSpawnSearch = function (search) {
    if (spawnSearchSetFlags(search.attributes, search.flags) !== 0)
      throw new Error('Spawn attribute flags cannot be restored');
  };
  Interceptor.attach(Module.getGlobalExportByName('posix_spawnp'), {
    onEnter(args) {
      const attributes = args[3];
      if (attributes.isNull())
        return;
      const flags = Memory.alloc(2);
      if (spawnSearchGetFlags(attributes, flags) !== 0)
        throw new Error('Spawn attribute flags are unreadable');
      this.spawnSearch = { attributes, flags: flags.readS16() };
      spawnSearches.set(this.threadId, this.spawnSearch);
    },
    onLeave() {
      if (this.spawnSearch === undefined)
        return;
      spawnSearches.delete(this.threadId);
      restoreSpawnSearch(this.spawnSearch);
    }
  });
  Interceptor.attach(Module.getGlobalExportByName('posix_spawn'), {
    onLeave() {
      const search = spawnSearches.get(this.threadId);
      if (search !== undefined)
        restoreSpawnSearch(search);
    }
  });
}
