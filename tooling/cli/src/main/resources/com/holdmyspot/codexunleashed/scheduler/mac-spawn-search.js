/* Keeps a gated image's PATH-searched spawns resumable.
 *
 * Frida's child gating adds POSIX_SPAWN_START_SUSPENDED to the caller's attribute object inside each posix_spawn call
 * and never removes it. posix_spawnp tries posix_spawn once per PATH entry with that same object, so after the first
 * miss the next attempt already carries the flag. Frida then records that the caller asked for a suspended child and its
 * resume leaves the child parked at its spawn breakpoint, as Cargo's `git` child was. Restoring the caller's own flags
 * after every attempt lets Frida see them unchanged on the next one.
 *
 * A thread stays inside posix_spawnp while Frida holds its gated child, so an image often forks while another thread is
 * inside it. The entry-patch prelude therefore redirects both functions, as it does for the other macOS hooks, instead
 * of Interceptor.attach, whose in-use count would stall every later Script load and unload in the fork child. */
// posix_spawnp first, then posix_spawn: the order of spawnSearchOperations and of the native originals table.
const spawnSearchOperations = ['posix_spawnp', 'posix_spawn'];
const spawnSearchOriginals = Memory.alloc(spawnSearchOperations.length * Process.pointerSize);
// Holds the pthread key whose per-thread value names the posix_spawnp call in progress. Each image that loads this
// script creates one key and never deletes it, because a thread may still be inside a replacement when it unloads.
// Script-level constants keep this key, the originals table and the CModule alive until the Script unloads.
const spawnSearchKey = Memory.alloc(Process.pointerSize);
const spawnSearchCreateKey = new NativeFunction(Module.getGlobalExportByName('pthread_key_create'), 'int',
  ['pointer', 'pointer']);
if (spawnSearchCreateKey(spawnSearchKey, NULL) !== 0)
  throw new Error('Spawn search thread key is unavailable');
const spawnSearchNative = new CModule(File.readAllBytes(@NATIVE_MODULE@), {
  originals: spawnSearchOriginals,
  search_key: spawnSearchKey,
  get_flags: Module.getGlobalExportByName('posix_spawnattr_getflags'),
  set_flags: Module.getGlobalExportByName('posix_spawnattr_setflags'),
  get_search: Module.getGlobalExportByName('pthread_getspecific'),
  set_search: Module.getGlobalExportByName('pthread_setspecific')
});
spawnSearchOperations.forEach((operation, index) => {
  installEntryPatch(operation, Module.getGlobalExportByName(operation), spawnSearchNative['replacement_' + operation],
    resume => spawnSearchOriginals.add(index * Process.pointerSize).writePointer(resume));
});
