/* This module executes only in an independently authenticated, held image.
 * MacDescriptorProtection supplies the exact configuration and the installed native
 * module's path as JSON literals. Its worker asset is packaged with this entrypoint. */
'use strict';

const configuration = @CONFIGURATION@;
const mappingBytes = 1024 * 1024;
const capacity = 65536;
const mmap = new NativeFunction(Module.getGlobalExportByName('mmap'), 'pointer',
  ['pointer', 'ulong', 'int', 'int', 'int', 'long']);
const mapping = configuration.inheritedRegistry === null
  ? mmap(NULL, mappingBytes, 3, 0x1002, -1, 0)
  : ptr(configuration.inheritedRegistry);
if (mapping.isNull() || mapping.equals(ptr(-1))) throw new Error('Native descriptor mapping unavailable');

const syscallNumber = Memory.alloc(4);
syscallNumber.writeS32(configuration.kqueueSyscall);
const native = new CModule(File.readAllBytes(@NATIVE_MODULE@), {
  registry: mapping,
  process_id: Module.getGlobalExportByName('getpid'),
  kernel_call: Module.getGlobalExportByName('syscall'),
  abandon_process: Module.getGlobalExportByName('abort'),
  original_close: Module.getGlobalExportByName('close'),
  error_location: Module.getGlobalExportByName('__error'),
  kernel_events: Module.getGlobalExportByName('kevent'),
  kqueue_number: syscallNumber
});
// Every registered observation closure reaches this owner through readHeader.
// It retains CModule code and imported allocated storage until Script unload
// has first reverted the native interception. The target mmap has process life.
const nativeOwner = { module: native, syscallNumber, snapshot: null };
const nativeSize = new NativeFunction(native.mapping_size, 'int', []);
if (nativeSize() > mappingBytes) throw new Error('Descriptor registry exceeds its mapping');
const initialize = new NativeFunction(native.initialize, 'int', ['int']);
const adopt = new NativeFunction(native.adopt, 'int', []);
nativeOwner.snapshot = new NativeFunction(native.snapshot, 'int', ['pointer']);
const seal = new NativeFunction(native.seal, 'int', ['int', 'pointer', 'int']);
const inventory = new NativeFunction(native.inventory, 'int', ['pointer', 'int']);
const validate = new NativeFunction(native.validate, 'int', ['int', 'pointer', 'int']);
if (configuration.inheritedRegistry === null && initialize(Process.id) !== 1)
  throw new Error('Descriptor registry initialization failed');
if (configuration.pid !== Process.id) throw new Error('Descriptor configuration names another process');
if (adopt() !== 1) throw new Error('Descriptor registry cannot enter recovered Script ownership');

const header = Memory.alloc(6 * 4);
function readHeader() {
  if (nativeOwner.snapshot(header) !== 1) throw new Error('Inherited descriptor registry is unauthenticated');
  return {
    pid: header.readS32(),
    mutations: header.add(4).readS32(),
    fault: header.add(8).readS32(),
    creators: header.add(12).readS32(),
    closers: header.add(16).readS32(),
    sealed: header.add(20).readS32()
  };
}

const originalHeader = readHeader();
if (originalHeader.pid !== Process.id || originalHeader.sealed !== 0 || originalHeader.fault !== 0)
  throw new Error('Held descriptor registry is not ready for admission');
const inheritedBuffer = Memory.alloc(capacity * 4);
const inheritedCount = inventory(inheritedBuffer, capacity);
if (inheritedCount < 0 || inheritedCount > capacity)
  throw new Error('Native early-fork inventory is incomplete');
const inheritedDescriptors = [];
for (let index = 0; index < inheritedCount; index++)
  inheritedDescriptors.push(inheritedBuffer.add(index * 4).readS32());

const queue = Module.getGlobalExportByName('kqueue');
if (Process.arch === 'arm64') {
  // The Darwin syscall veneer is intentionally replaced in full. Gum's forced
  // relocation option is restricted to this authenticated veneer, and its
  // returned trampoline is never called. Forwarding uses the native SDK number.
  const first = Instruction.parse(queue);
  const second = Instruction.parse(first.next);
  if (first.toString() !== 'mov x16, #0x' + configuration.kqueueSyscall.toString(16) ||
      second.toString() !== 'svc #0x80')
    throw new Error('Unsupported Darwin kqueue syscall veneer');
  Interceptor.replaceFast({ target: queue, relocation: 'forced' }, native.create_queue);
} else if (Process.arch === 'x64') {
  const first = Instruction.parse(queue);
  const second = Instruction.parse(first.next);
  const systemCall = second.toString() === 'mov r10, rcx' ? Instruction.parse(second.next) : second;
  if (first.toString() !== 'mov eax, 0x' + (0x2000000 + configuration.kqueueSyscall).toString(16) ||
      systemCall.toString() !== 'syscall')
    throw new Error('Unsupported Darwin kqueue syscall veneer: ' + first + '; ' + second + '; ' + systemCall);
  Interceptor.replaceFast({ target: queue, relocation: 'forced' }, native.create_queue);
} else {
  throw new Error('Unsupported native descriptor architecture');
}
// Ordinary replacement preserves the existing FileDescriptorGuard listeners
// and observes native runtime closes even while ordinary listeners are ignored.
Interceptor.replace(Module.getGlobalExportByName('close'), native.close_descriptor);
Interceptor.flush();

/* The host owns a separate bootstrap Script and its real runtime worker.
 * Closing that Script terminates its worker through the supported native
 * lifecycle while these hooks remain installed. No worker-private API or
 * dependency patch is needed to observe the legitimate close call sites. */
recv('mac-descriptor-bootstrap-completed', message => {
  if (message.marker !== configuration.marker || message.pid !== Process.id)
    throw new Error('Invalid descriptor bootstrap completion');
  const state = readHeader();
  if (state.fault !== 0 || state.creators === 0 || state.closers === 0) {
    const owned = [];
    const count = inventory(inheritedBuffer, capacity);
    for (let i = 0; i < count; i++) owned.push(inheritedBuffer.add(i * 4).readS32());
    throw new Error('Native descriptor call-path admission is incomplete: ' + JSON.stringify({state, owned}));
  }
  send({ kind: 'mac-descriptor-ready', marker: configuration.marker, pid: Process.id,
    registry: mapping.toString(), mutations: state.mutations, creators: state.creators,
    closers: state.closers, inheritedDescriptors });
});

function descriptorRequest(message) {
  if (message.marker !== configuration.marker || message.pid !== Process.id ||
      !Number.isInteger(message.mutations) || !Array.isArray(message.descriptors) ||
      message.descriptors.length > capacity)
    throw new Error('Invalid descriptor admission request');
  const descriptors = Memory.alloc(Math.max(1, message.descriptors.length) * 4);
  const unique = new Set();
  for (let index = 0; index < message.descriptors.length; index++) {
    const fd = message.descriptors[index];
    if (!Number.isInteger(fd) || fd < 0 || fd >= capacity || unique.has(fd))
      throw new Error('Invalid descriptor inventory member');
    unique.add(fd);
    descriptors.add(index * 4).writeS32(fd);
  }
  return descriptors;
}

function receiveSeal() {
  recv('mac-descriptor-seal', message => {
    const descriptors = descriptorRequest(message);
    if (seal(message.mutations, descriptors, message.descriptors.length) !== 1) {
      const state = readHeader();
      if (state.fault !== 0 || state.sealed !== 0) throw new Error('Native descriptor seal state is invalid');
      receiveSeal();
      send({ kind: 'mac-descriptor-seal-retry', marker: configuration.marker, pid: Process.id,
        mutations: state.mutations });
      return;
    }
    send({ kind: 'mac-descriptor-sealed', marker: configuration.marker, pid: Process.id });
  });
}
receiveSeal();

recv('mac-descriptor-observe', message => {
  if (message.marker !== configuration.marker || message.pid !== Process.id)
    throw new Error('Invalid descriptor observation request');
  const state = readHeader();
  send({ kind: 'mac-descriptor-observed', marker: configuration.marker, pid: Process.id,
    mutations: state.mutations, fault: state.fault, sealed: state.sealed });
});

function receiveValidation() {
  recv('mac-descriptor-validate', message => {
    const descriptors = descriptorRequest(message);
    if (validate(message.mutations, descriptors, message.descriptors.length) !== 1) {
      const state = readHeader();
      if (state.fault !== 0 || state.sealed !== 1) throw new Error('Native descriptor validation state is invalid');
      receiveValidation();
      send({ kind: 'mac-descriptor-validate-retry', marker: configuration.marker, pid: Process.id,
        mutations: state.mutations });
      return;
    }
    send({ kind: 'mac-descriptor-validated', marker: configuration.marker, pid: Process.id });
  });
}
receiveValidation();

send({ kind: 'mac-descriptor-installed', marker: configuration.marker, pid: Process.id,
  registry: mapping.toString(), inheritedDescriptors });
