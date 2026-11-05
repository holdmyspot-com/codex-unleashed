/**
 * Mediates Darwin libc jobserver transfers for one independently verified image.
 * The controller injects cargoTokenIdentity with attempt, installation, pid and
 * fifo strings. The FIFO value is device:0:inode from its retained descriptor.
 * The process must remain held until ready is observed on this script connection.
 * Requests and controls use version 1 and exact installation identities. Invalid
 * controls report rejection and leave the operation parked. Descriptor metadata
 * failures and selected transfers larger than one byte park without native I/O;
 * the owner must terminate the workload after either failure. Zero-byte calls and
 * known unrelated descriptors retain their original libc behavior.
 */

/** Namespace separating jobserver events from allocation events. */
const cargoTokenProtocol = 'codex-build-cargo-tokens';
/** Supported event/control wire version. */
const cargoTokenVersion = 1;
/** Darwin libSystem supplies descriptor-based IO; stat uses its 64-bit-inode ABI. */
const cargoTokenLibc = Process.getModuleByName('libSystem.B.dylib');
/**
 * Explicit inode64 alias on Intel, sole 64-bit ABI on ARM64. Descriptor metadata is read from the retained handle,
 * never a claimed pathname.
 */
const cargoTokenStatAddress = Process.arch === 'x64' ?
    cargoTokenLibc.getExportByName('fstat$INODE64') : cargoTokenLibc.getExportByName('fstat');
if (Process.platform !== 'darwin' || (Process.arch !== 'x64' && Process.arch !== 'arm64'))
    throw new Error('Cargo token mediation requires Darwin ARM64 or Intel64');
/** Exact monotonically increasing request number within this installation. */
let cargoTokenSequence = uint64(0);

if (cargoTokenIdentity.pid !== String(Process.id))
    throw new Error('Configured Cargo token process differs from actual Process.id');

/**
 * Sends one event with this script's configured identities and actual process ID.
 * @param event event-specific fields, which must not replace identity fields
 */
function sendCargoTokenEvent(event) {
    send(Object.assign({}, event, {protocol: cargoTokenProtocol, version: cargoTokenVersion,
        attempt: cargoTokenIdentity.attempt, installation: cargoTokenIdentity.installation,
        pid: String(Process.id)}));
}

/**
 * Reserves a positive signed-long sequence; exhaustion parks without native I/O.
 * @return canonical decimal sequence unique within this installation
 */
function nextCargoTokenSequence() {
    if (cargoTokenSequence.compare(uint64('9223372036854775807')) >= 0) {
        sendCargoTokenEvent({event: 'failure', cause: 'Request sequence exhausted'});
        parkCargoTokenFailure();
    }
    cargoTokenSequence = cargoTokenSequence.add(1);
    return cargoTokenSequence.toString();
}

/**
 * Retains the blocked native call after an unsupported or untrackable operation.
 * Controls cannot release this failure; the controller owns workload termination.
 */
function parkCargoTokenFailure() {
    while (true)
        recv('cargo-token-failure', function ignoreFailureControl() {}).wait();
}

/**
 * Indicates whether a control exactly authorizes this pending one-byte operation.
 * @param message copied Frida control object
 * @param sequence current native request's canonical sequence
 * @param operation read or write requested by the held call
 * @return true only for the exact identities, operation and valid byte shape
 */
function acceptsCargoTokenControl(message, sequence, operation) {
    if (message === null || typeof message !== 'object' || Array.isArray(message))
        return false;
    const expected = ['type', 'protocol', 'version', 'attempt', 'installation', 'pid', 'sequence', 'operation'];
    if (operation === 'read')
        expected.push('byte');
    const actual = Object.keys(message).sort();
    const hasExactFields = JSON.stringify(actual) === JSON.stringify(expected.sort());
    if (!hasExactFields)
        return false;
    const matchesIdentity = message.type === ('cargo-token-control-' + sequence) &&
        message.protocol === cargoTokenProtocol && message.version === cargoTokenVersion &&
        message.attempt === cargoTokenIdentity.attempt &&
        message.installation === cargoTokenIdentity.installation &&
        message.pid === String(Process.id) && message.sequence === sequence &&
        message.operation === operation;
    if (!matchesIdentity)
        return false;
    if (operation === 'write')
        return true;
    return Number.isInteger(message.byte) && message.byte >= 0 && message.byte <= 255;
}

/**
 * Waits for an exact control, preserving the parked operation after rejection.
 * @param sequence pending request identity
 * @param operation intercepted operation
 * @return exact accepted control, including the delivered byte for a read
 */
function awaitCargoTokenControl(sequence, operation) {
    let accepted;
    while (accepted === undefined) {
        recv('cargo-token-control-' + sequence, function receiveCargoTokenControl(message) {
            if (acceptsCargoTokenControl(message, sequence, operation))
                accepted = message;
            else
                sendCargoTokenEvent({event: 'control-rejected', sequence: sequence, operation: operation});
        }).wait();
    }
    return accepted;
}

/** Operation codes shared with the native transfer replacements. */
const cargoTokenOperations = ['read', 'write'];

/**
 * Mediates one selected one-byte FIFO transfer on behalf of the native replacement.
 * @param operationCode index of the intercepted operation in cargoTokenOperations
 * @param buffer caller's transfer buffer
 * @param count caller's requested byte count
 * @return the one byte transferred
 */
function mediateCargoTokenTransfer(operationCode, buffer, count) {
    const operation = cargoTokenOperations[operationCode];
    const sequence = nextCargoTokenSequence();
    if (count.toString() !== '1') {
        sendCargoTokenEvent({event: 'failure', cause: 'Jobserver transfer count differs from one',
            operation: operation, sequence: sequence});
        parkCargoTokenFailure();
    }
    const request = {event: 'transfer', sequence: sequence, operation: operation};
    if (operation === 'write')
        request.byte = buffer.readU8();
    sendCargoTokenEvent(request);
    const reply = awaitCargoTokenControl(sequence, operation);
    if (operation === 'read')
        buffer.writeU8(reply.byte);
    return 1;
}

/**
 * Reports unreadable descriptor metadata for the native replacement and parks its call.
 * @param operationCode index of the intercepted operation in cargoTokenOperations
 */
function failCargoTokenInspection(operationCode) {
    sendCargoTokenEvent({event: 'failure', cause: 'Descriptor statx failed',
        operation: cargoTokenOperations[operationCode], sequence: nextCargoTokenSequence()});
    parkCargoTokenFailure();
}

const cargoTokenFifoFields = cargoTokenIdentity.fifo.split(':');
const cargoTokenFifoDevice = Memory.alloc(4).writeU32(Number(cargoTokenFifoFields[0]));
const cargoTokenFifoInode = Memory.alloc(8).writeU64(uint64(cargoTokenFifoFields[2]));
/** Entry points that resume each original veneer, published before its entry is patched. */
const cargoTokenOriginals = Memory.alloc(2 * Process.pointerSize);
const cargoTokenMediate = new NativeCallback(mediateCargoTokenTransfer, 'long', ['int', 'pointer', 'ulong']);
const cargoTokenFail = new NativeCallback(failCargoTokenInspection, 'void', ['int']);
/*
 * WORKAROUND: https://github.com/frida/frida-gum/issues/474
 * Interceptor.attach and Interceptor.replace keep a function's trampoline in use while any thread is inside the
 * function, and Script unload and Session detach wait until no trampoline is in use. A process whose other thread is
 * blocked in read or write when it forks, such as git fetch's sideband reader or rustc's jobserver helper when rustc
 * forks its linker, gives its child an in-use count that only the vanished thread could release. Every later unload
 * in that child then waits forever, which held the scheduler in the child's bootstrap Session detach. replaceFast
 * keeps no such count, but Gum accepts it on these veneers only with forced relocation, which overwrites 16 bytes: a
 * thread already blocked in the svc then returns into the redirect's address literal and dies with SIGILL. This
 * script therefore patches only each veneer's first 8 bytes itself, as Gum's default hook does, so a blocked thread
 * resumes at unchanged code and no Gum bookkeeping survives a fork. Its replacement also runs for Frida's own
 * threads, which attach listeners skip, so unrelated descriptors stay in this native code and only the jobserver FIFO
 * enters JavaScript.
 */
const cargoTokenNative = new CModule(`
typedef long (*Transfer) (int fd, void * buffer, unsigned long count);

extern int descriptor_stat (int fd, void * data);
extern int * error_location (void);
extern long mediate (int operation, void * buffer, unsigned long count);
extern void fail (int operation);
extern unsigned int fifo_device;
extern unsigned long long fifo_inode;
extern Transfer originals[2];

static long transfer (int operation, int fd, void * buffer, unsigned long count)
{
  Transfer original = originals[operation];
  if (count == 0)
    return original (fd, buffer, count);
  int saved = *error_location ();
  /* Darwin __DARWIN_STRUCT_STAT64 begins dev32, mode16, links16, inode64 on both supported ABIs. */
  unsigned long long data[18];
  int status = descriptor_stat (fd, data);
  int failure = *error_location ();
  *error_location () = saved;
  if (status != 0)
  {
    if (failure == 9)
      return original (fd, buffer, count);
    fail (operation);
  }
  unsigned char * bytes = (unsigned char *) data;
  int fifo = ((*(unsigned short *) (bytes + 4)) & 0xf000) == 0x1000;
  if (!fifo || *(unsigned int *) bytes != fifo_device || *(unsigned long long *) (bytes + 8) != fifo_inode)
    return original (fd, buffer, count);
  long result = mediate (operation, buffer, count);
  *error_location () = saved;
  return result;
}

long replacement_read (int fd, void * buffer, unsigned long count)
{
  return transfer (0, fd, buffer, count);
}

long replacement_write (int fd, void * buffer, unsigned long count)
{
  return transfer (1, fd, buffer, count);
}
`, {
    descriptor_stat: cargoTokenStatAddress,
    error_location: cargoTokenLibc.getExportByName('__error'),
    mediate: cargoTokenMediate,
    fail: cargoTokenFail,
    fifo_device: cargoTokenFifoDevice,
    fifo_inode: cargoTokenFifoInode,
    originals: cargoTokenOriginals
});
/** Darwin syscall numbers of the replaced operations, in cargoTokenOperations order. */
const cargoTokenSyscalls = [3, 4];
/** Bytes of each veneer replaced by the branch to its stub: adrp and br on ARM64, a rel32 jmp on Intel. */
const cargoTokenPatchLength = Process.arch === 'arm64' ? 8 : 5;
/** Most distance between a veneer and its stub that the patched branch reaches. */
const cargoTokenStubReach = Process.arch === 'arm64' ? 0xfff00000 : 0x7ff00000;
/** Stub offset of the code that runs the replaced bytes and resumes the veneer after them. */
const cargoTokenResumeOffset = 0x40;
/** Stubs of this installation, retained for the image's lifetime. */
const cargoTokenStubs = [];

/**
 * Returns the stub that a patched veneer branches to.
 * @param target veneer entry point
 * @return the branch destination, or null when the entry is not the branch this script writes
 */
function cargoTokenPatchDestination(target) {
    if (Process.arch === 'arm64') {
        const first = Instruction.parse(target);
        const second = Instruction.parse(first.next);
        if (!first.toString().startsWith('adrp x17, ') || second.toString() !== 'br x17')
            return null;
        const word = target.readU32();
        let pages = (((word >>> 5) & 0x7ffff) << 2) | ((word >>> 29) & 3);
        if (pages >= 0x100000)
            pages -= 0x200000;
        return target.and(ptr('0xfffffffffffff000')).add(int64(pages).shl(12));
    }
    if (target.readU8() !== 0xe9)
        return null;
    return target.add(5).add(target.add(1).readS32());
}

/**
 * Rejects an entry point that is not the plain Darwin syscall veneer. A fork child sees the original veneer, because
 * Frida unloads the inherited parent script in the child, and unloading restores the original bytes.
 * @param operation intercepted operation name
 * @param target veneer entry point
 * @param number the operation's Darwin syscall number
 * @return the original bytes that the patch replaces
 */
function cargoTokenReplacedBytes(operation, target, number) {
    const first = Instruction.parse(target);
    const second = Instruction.parse(first.next);
    if (Process.arch === 'arm64') {
        if ((first.toString() === 'mov x16, #' + number || first.toString() === 'mov x16, #0x' + number.toString(16)) &&
            second.toString() === 'svc #0x80')
            return target.readByteArray(cargoTokenPatchLength);
    } else {
        const systemCall = second.toString() === 'mov r10, rcx' ? Instruction.parse(second.next) : second;
        if (first.size === cargoTokenPatchLength &&
            first.toString() === 'mov eax, 0x' + (0x2000000 + number).toString(16) && systemCall.toString() === 'syscall')
            return target.readByteArray(cargoTokenPatchLength);
    }
    throw new Error('Unsupported Darwin ' + operation + ' syscall veneer: ' + first + '; ' + second);
}

/**
 * Writes a stub that enters the replacement, and code that runs the replaced bytes and resumes the veneer after them,
 * then publishes that code before branching the veneer's entry to the stub.
 * @param index operation index in cargoTokenOperations
 * @param target veneer entry point
 * @param replaced the veneer's original replaced bytes
 */
function installCargoTokenStub(index, target, replaced) {
    const stub = Memory.alloc(Process.pageSize, {near: target, maxDistance: cargoTokenStubReach});
    cargoTokenStubs.push(stub);
    const replacement = cargoTokenNative['replacement_' + cargoTokenOperations[index]];
    const resume = stub.add(cargoTokenResumeOffset);
    Memory.protect(stub, Process.pageSize, 'r-x');
    Memory.patchCode(stub, Process.pageSize, code => {
        if (Process.arch === 'arm64') {
            const entry = new Arm64Writer(code, {pc: stub});
            entry.putLdrRegAddress('x17', replacement);
            entry.putBrReg('x17');
            entry.flush();
            const after = new Arm64Writer(code.add(cargoTokenResumeOffset), {pc: resume});
            after.putBytes(replaced);
            after.putLdrRegAddress('x17', target.add(cargoTokenPatchLength));
            after.putBrReg('x17');
            after.flush();
        } else {
            const entry = new X86Writer(code, {pc: stub});
            entry.putJmpAddress(replacement);
            entry.flush();
            const after = new X86Writer(code.add(cargoTokenResumeOffset), {pc: resume});
            after.putBytes(replaced);
            after.putJmpAddress(target.add(cargoTokenPatchLength));
            after.flush();
        }
    });
    cargoTokenOriginals.add(index * Process.pointerSize).writePointer(resume);
    Memory.patchCode(target, cargoTokenPatchLength, code => {
        if (Process.arch === 'arm64') {
            const branch = new Arm64Writer(code, {pc: target});
            branch.putAdrpRegAddress('x17', stub);
            branch.putBrReg('x17');
            branch.flush();
            if (branch.offset !== cargoTokenPatchLength)
                throw new Error('Unexpected Darwin veneer branch length');
        } else {
            code.writeU8(0xe9);
            code.add(1).writeS32(stub.sub(target.add(5)).toInt32());
        }
    });
    if (!cargoTokenPatchDestination(target).equals(stub))
        throw new Error('Darwin veneer branch does not reach its stub');
}

/** Never collected, so its weak callback runs only when this script unloads. */
const cargoTokenPatchOwner = {};
/** Patched veneers with their original bytes, restored when this script unloads. */
const cargoTokenPatches = [];
Script.bindWeak(cargoTokenPatchOwner, () => {
    for (const patch of cargoTokenPatches)
        Memory.patchCode(patch.target, cargoTokenPatchLength, code => code.writeByteArray(patch.replaced));
});
cargoTokenOperations.forEach((operation, index) => {
    const target = cargoTokenLibc.getExportByName(operation);
    const replaced = cargoTokenReplacedBytes(operation, target, cargoTokenSyscalls[index]);
    cargoTokenPatches.push({target: target, replaced: replaced});
    installCargoTokenStub(index, target, replaced);
});
sendCargoTokenEvent({event: 'ready'});
