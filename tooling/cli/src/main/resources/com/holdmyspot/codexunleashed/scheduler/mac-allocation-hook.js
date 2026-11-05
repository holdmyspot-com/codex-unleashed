'use strict';

/*
 * One verified macOS/libSystem process-image installation, initialized by AllocationHook's serialized allocationIdentity.
 * Only positive-size malloc NULL/ENOMEM waits. This resource does not cover every allocator or signal handler
 * allocation. The entry-patch prelude routes every thread's malloc through the replacement, so a denial on one of
 * Frida's own threads also waits, and it can be granted only while the script's JavaScript thread still runs.
 * Eight slots bound simultaneous denied invocations, not Java transport or lifetime identity collections.
 * Keep this patch, CModule and backing alive until the attempt establishes whole-workload execution is impossible.
 */
if (Process.pointerSize !== 8)
  throw new Error('Allocation hook requires 64-bit macOS/libSystem');

const maximumWait = uint64('9223372036854775807');
const zero = uint64('0');

/** Read a canonical positive decimal native identity without losing unsigned magnitudes. */
function positiveInteger(raw, maximum) {
  if (typeof raw !== 'string')
    throw new Error('Allocation identity must be a decimal string');
  const value = uint64(raw);
  if (value.compare(zero) <= 0 || value.compare(maximum) > 0 || value.toString() !== raw)
    throw new Error('Allocation identity is outside its canonical positive range');
  return value;
}

const identityFields = ['attempt', 'installation', 'pid'];
if (Object.keys(allocationIdentity).length !== identityFields.length ||
    identityFields.some(field => !Object.prototype.hasOwnProperty.call(allocationIdentity, field)))
  throw new Error('Allocation initialization has unexpected fields');
if (typeof allocationIdentity.attempt !== 'string' ||
    !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(allocationIdentity.attempt))
  throw new Error('Allocation initialization requires a canonical attempt UUID');
positiveInteger(allocationIdentity.installation, maximumWait);
positiveInteger(allocationIdentity.pid, uint64('4294967295'));
if (allocationIdentity.pid !== Process.id.toString())
  throw new Error('Allocation initialization identifies a different process');

const libc = Process.findModuleByName('libsystem_c.dylib');
if (libc === null)
  throw new Error('Allocation hook requires loaded libsystem_c.dylib');
/** Follow the target's global allocator binding, including executable interposition used by rustc. */
const mallocAddress = Module.getGlobalExportByName('malloc');
/* Preallocated bound validated against actual native sizeof before initialization or entry patching. */
const registryBytes = 16384;
const backing = Memory.alloc(registryBytes);
backing.writeByteArray(new Uint8Array(registryBytes));
/** Resume address that calls the original malloc, published before its entry is patched. */
const originalMalloc = Memory.alloc(Process.pointerSize);
const nativeModule = new CModule(File.readAllBytes(@NATIVE_MODULE@), {
  registry: backing,
  original_malloc: originalMalloc,
  errno_location: Module.getGlobalExportByName('__error'),
  native_thread_id: Module.getGlobalExportByName('pthread_self')
});

/** Bind only control/query operations, called from the script thread without Interceptor traps. */
function control(name, result, arguments_) {
  return new NativeFunction(nativeModule[name], result, arguments_, { traps: 'none' });
}

const nativeSize = control('registry_size', 'uint', [])();
if (nativeSize > registryBytes)
  throw new Error('Native allocation registry exceeds preallocated backing');
const publicationSize = control('publication_size', 'uint', [])();
if (publicationSize !== 32)
  throw new Error('Native allocation publication layout differs from 64-bit Linux contract');
const publication = Memory.alloc(publicationSize);
control('initialize', 'void', [])();
const faultCode = control('fault_code', 'int', []);
const nextPublication = control('next_publication', 'int', ['pointer']);
const nativeGrant = control('grant', 'int', ['uint64']);

/** Send only strict version-1 allocation payloads accepted by the existing Java codec. */
function emit(event, details = {}) {
  send({ protocol: 'codex-build-allocation', version: 1, attempt: allocationIdentity.attempt,
    installation: allocationIdentity.installation, pid: allocationIdentity.pid, event, ...details });
}

/** Validate one identity-exact permission before any native effect; malformed controls become terminal script errors. */
function receivePermission() {
  recv('allocation-control', message => {
    const fields = ['type', 'version', 'attempt', 'installation', 'pid', 'operation', 'wait'];
    if (Object.keys(message).length !== fields.length ||
        fields.some(field => !Object.prototype.hasOwnProperty.call(message, field)))
      throw new Error('Allocation control has unexpected fields');
    if (message.type !== 'allocation-control' || message.version !== 1 || message.attempt !== allocationIdentity.attempt ||
        message.installation !== allocationIdentity.installation || message.pid !== allocationIdentity.pid ||
        message.operation !== 'grant')
      throw new Error('Allocation control has wrong identity, version or operation');
    const id = positiveInteger(message.wait, maximumWait);
    const result = nativeGrant(id);
    if (result === -1) {
      clearInterval(publicationTimer);
      throw new Error('Allocation registry fault ' + faultCode());
    }
    const statuses = ['unknown', 'accepted', 'duplicate'];
    if (result < 0 || result >= statuses.length)
      throw new Error('Native allocation permission result is outside its range');
    emit('granted', { wait: message.wait, status: statuses[result] });
    receivePermission();
  });
}

/* Reachability retains all executable state. There is intentionally no dispose/unload while target tasks can execute. */
installEntryPatch('malloc', mallocAddress, nativeModule.replacement_malloc,
  resume => originalMalloc.writePointer(resume));
receivePermission();
const publicationTimer = setInterval(() => {
  if (faultCode() !== 0) {
    clearInterval(publicationTimer);
    throw new Error('Allocation registry fault ' + faultCode());
  }
  for (let count = 0; count < 8; count++) {
    const result = nextPublication(publication);
    if (result === 0)
      return;
    if (result !== 1) {
      clearInterval(publicationTimer);
      throw new Error('Allocation registry fault ' + faultCode());
    }
    emit('blocked', { wait: publication.readU64().toString(), thread: publication.add(8).readU64().toString(),
      bytes: publication.add(16).readU64().toString(), error: publication.add(24).readU32().toString() });
  }
}, 10);
emit('ready');
