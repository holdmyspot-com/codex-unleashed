'use strict';
/**
 * Observes the pinned native creation boundary. Attach before any child-gating subscriber. No callback waits for acknowledgement.
 * The controller substitutes one unpredictable observer generation before loading this exact frozen source.
 */
const generation = '__OBSERVER_GENERATION__';
if (Process.platform !== 'windows' || !['ia32', 'x64', 'arm64'].includes(Process.arch))
  throw new Error('Creation observer supports only pinned Windows x86, x64 and ARM64 layouts');
const kernel = Process.getModuleByName('kernel32.dll');
/** Windows APIs use stdcall in 32-bit images, which Frida's default ABI does not select there. */
const options = { abi: Process.arch === 'ia32' ? 'stdcall' : 'default', scheduling: 'cooperative', traps: 'none' };
/*
 * STARTUPINFOW offsets for this image's pointer size: cb, three pointers, seven DWORDs, dwFlags, two WORDs,
 * lpReserved2, then the three standard handles (104 bytes in 64-bit images, 68 in 32-bit ones).
 */
const pointer = Process.pointerSize;
const startupFlags = 4 * pointer + 28;
const startupInput = (pointer === 8 ? 72 : 52) + pointer;
const startupSize = startupInput + 3 * pointer;
/** Resolves a public helper without allowing its calls to reenter interceptors. */
function native(name, result, args) {
  return new NativeFunction(kernel.getExportByName(name), result, args, options);
}
const current = native('GetCurrentProcess', 'pointer', []);
const processId = native('GetProcessId', 'uint', ['pointer']);
const times = native('GetProcessTimes', 'int', ['pointer', 'pointer', 'pointer', 'pointer', 'pointer']);
const duplicate = native('DuplicateHandle', 'int', ['pointer', 'pointer', 'pointer', 'pointer', 'uint', 'int', 'uint']);
const close = native('CloseHandle', 'int', ['pointer']);
const information = native('GetHandleInformation', 'int', ['pointer', 'pointer']);
const getCommandLine = native('GetCommandLineW', 'pointer', []);
const getEnvironment = native('GetEnvironmentStringsW', 'pointer', []);
const freeEnvironment = native('FreeEnvironmentStringsW', 'int', ['pointer']);
const getDirectory = native('GetCurrentDirectoryW', 'uint', ['uint', 'pointer']);
/** Copies a bounded UTF-16 environment block without narrowing native values or losing drive entries. */
function environmentEntries(block) {
  if (block.isNull()) throw new Error('Native environment block is NULL');
  const entries = [];
  let start = 0;
  for (let index = 0; index < 262144; ++index) {
    if (block.add(index * 2).readU16() !== 0) continue;
    if (index === start) return entries;
    entries.push(block.add(start * 2).readUtf16String(index - start));
    start = index + 1;
  }
  throw new Error('Native environment block exceeds its bound');
}
/** Obtains the actual inherited environment while owning and releasing its native snapshot. */
function inheritedEnvironment() {
  const block = getEnvironment();
  try { return environmentEntries(block); }
  finally {
    if (!block.isNull() && freeEnvironment(block) === 0)
      throw new Error('FreeEnvironmentStringsW failed');
  }
}
/** Copies the creating process's actual directory for a NULL native directory argument. */
function inheritedDirectory() {
  const buffer = Memory.alloc(65536);
  const length = getDirectory(32768, buffer);
  if (length === 0 || length >= 32768) throw new Error('GetCurrentDirectoryW failed or exceeded its bound');
  return buffer.readUtf16String(length);
}
/** Copies nullable native text before CreateProcessInternalW may modify the command line. */
function nullableText(value) {
  return value.isNull() ? null : value.readUtf16String();
}
const ledger = new Map();
let sequence = 0;
let failed = false;
/** Returns exact FILETIME text without passing its 64-bit count through a JavaScript Number. */
function birth(handle) {
  const data = Memory.alloc(32);
  if (times(handle, data, data.add(8), data.add(16), data.add(24)) === 0)
    throw new Error('GetProcessTimes failed');
  return data.readU64().toString();
}
/** Publishes sticky failure without retrying an acquisition, release or acknowledgement effect. */
function fail(message) {
  failed = true;
  send({ kind: 'failure', generation, message });
}
let target = null;
let moduleName = null;
for (const name of ['kernelbase.dll', 'kernel32.dll']) {
  const candidate = Process.findModuleByName(name);
  if (candidate === null) continue;
  const address = candidate.findExportByName('CreateProcessInternalW');
  if (address !== null) { target = address; moduleName = candidate.name; break; }
}
if (target === null) throw new Error('Pinned private CreateProcessInternalW boundary unavailable');
const parent = current();
const parentPid = processId(parent);
const parentBirth = birth(parent);
const listener = Interceptor.attach(target, {
  /** Saves invocation-local output storage; the later gate listener may force CREATE_SUSPENDED. */
  onEnter(args) {
    const savedError = this.lastError;
    try {
      this.information = args[10];
      this.originalFlags = args[6].toUInt32();
      const startup = args[9];
      if (startup.isNull() || startup.readU32() < startupSize)
        throw new Error('Pinned STARTUPINFO layout is absent');
      const inheritsEnvironment = args[7].isNull();
      if (!inheritsEnvironment && (this.originalFlags & 0x400) === 0)
        throw new Error('Explicit non-Unicode environment is outside the pinned Cargo boundary');
      const standardHandles = (startup.add(startupFlags).readU32() & 0x100) !== 0;
      this.launch = {
        application: nullableText(args[1]), commandLine: nullableText(args[2]),
        inheritedEnvironment: inheritsEnvironment, inheritedDirectory: args[8].isNull(),
        directory: args[8].isNull() ? inheritedDirectory() : args[8].readUtf16String(),
        environment: inheritsEnvironment ? inheritedEnvironment() : environmentEntries(args[7]),
        standardHandles,
        input: standardHandles ? startup.add(startupInput).readPointer().toString() : '0x0',
        output: standardHandles ? startup.add(startupInput + pointer).readPointer().toString() : '0x0',
        error: standardHandles ? startup.add(startupInput + 2 * pointer).readPointer().toString() : '0x0'
      };
    } catch (error) {
      fail(String(error));
    } finally {
      this.lastError = savedError;
    }
  },
  /** Retains a copy before the later gate listener acknowledges; originals remain strictly borrowed. */
  onLeave(result) {
    const savedError = this.lastError;
    try {
      if (failed || result.toInt32() === 0) return;
      if (ledger.size >= 1024 || sequence >= Number.MAX_SAFE_INTEGER)
        throw new Error('Creation receipt ledger limit reached; gate must remain held');
      const id = ++sequence;
      const entry = { state: 'acquiring', handle: null };
      ledger.set(id, entry);
      const original = this.information.readPointer();
      const thread = this.information.add(Process.pointerSize).readPointer();
      const childPid = this.information.add(Process.pointerSize * 2).readU32();
      const out = Memory.alloc(Process.pointerSize);
      if (duplicate(parent, original, parent, out, 0x101040, 0, 0) === 0)
        throw new Error('DuplicateHandle failed; gate must remain held');
      entry.handle = out.readPointer();
      entry.state = 'owned';
      const flags = Memory.alloc(4);
      if (information(entry.handle, flags) === 0 || (flags.readU32() & 1) !== 0)
        throw new Error('Owned process copy must be noninheritable');
      if (processId(entry.handle) !== childPid) throw new Error('Returned native PID differs');
      send({ kind: 'retained', generation, sequence: id, parentPid, parentBirth, childPid,
        childBirth: birth(entry.handle), sourceHandle: entry.handle.toString(), originalHandle: original.toString(),
        originalThread: thread.toString(), originalFlags: this.originalFlags, module: moduleName,
        address: target.toString(), requiredRights: '0x101040', originalHandlesUntouched: true, launch: this.launch });
    } catch (error) {
      fail(String(error));
    } finally {
      this.lastError = savedError;
    }
  }
});
/** Accepts exactly one authenticated observer generation/sequence release; stale requests have no native effects. */
function receive() {
  recv('release', message => {
    try {
      if (message === null || typeof message !== 'object' ||
          Object.keys(message).sort().join(',') !== 'payload,type' || message.type !== 'release')
        throw new Error('Release request requires exact type and payload fields');
      const request = message.payload;
      if (request === null || typeof request !== 'object' ||
          Object.keys(request).sort().join(',') !== 'generation,sequence' ||
          typeof request.generation !== 'string' || !Number.isSafeInteger(request.sequence) || request.sequence <= 0)
        throw new Error('Release payload requires generation and positive safe sequence');
      const entry = ledger.get(request.sequence);
      if (failed || request.generation !== generation || entry === undefined || entry.state !== 'owned') {
        send({ kind: 'rejected-release', generation, sequence: request.sequence });
      } else {
        entry.state = 'close-attempted';
        const handle = entry.handle;
        if (close(handle) === 0) throw new Error('CloseHandle failed; never replay');
        entry.state = 'closed';
        ledger.delete(request.sequence);
        send({ kind: 'released', generation, sequence: request.sequence });
      }
    } catch (error) {
      fail(String(error));
    }
    receive();
  });
}
receive();
send({ kind: 'ready', generation, parentPid, parentBirth, module: moduleName, address: target.toString(),
  boundary: 'private-pinned-CreateProcessInternalW', pointerSize: Process.pointerSize,
  state: { commandLine: getCommandLine().readUtf16String(), directory: inheritedDirectory(),
    environment: inheritedEnvironment() } });
