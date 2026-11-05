/**
 * Mediates Linux libc jobserver transfers for one independently verified image.
 * The controller injects cargoTokenIdentity with attempt, installation, pid and
 * fifo strings. The FIFO value is major:minor:inode from its retained descriptor.
 * The process must remain held until ready is observed on this script connection.
 * Requests and controls use version 1 and exact installation identities. Invalid
 * controls report rejection and leave the operation parked. A read reports whether
 * its descriptor is O_NONBLOCK; the controller answers a nonblocking read at once,
 * and a control without a byte makes it fail with EAGAIN as the native read of an
 * empty FIFO would. Descriptor metadata or flag failures and selected transfers
 * larger than one byte park without native I/O;
 * the owner must terminate the workload after either failure. Zero-byte calls and
 * known unrelated descriptors retain their original libc behavior.
 */

/** Namespace separating jobserver events from allocation events. */
const cargoTokenProtocol = 'codex-build-cargo-tokens';
/** Supported event/control wire version. */
const cargoTokenVersion = 1;
/** libc supplies the Linux LP64 read, write and statx operations. */
const cargoTokenLibc = Process.getModuleByName('libc.so.6');
/** Linux statx preserves descriptor identity across path changes. */
const cargoTokenStatx = new SystemFunction(cargoTokenLibc.getExportByName('statx'), 'int',
    ['int', 'pointer', 'int', 'uint', 'pointer']);
/** fcntl(F_GETFL) reports whether a read's descriptor is O_NONBLOCK. */
const cargoTokenFcntl = new SystemFunction(cargoTokenLibc.getExportByName('fcntl'), 'int', ['int', 'int']);
/** Linux F_GETFL command. */
const cargoTokenGetFlags = 3;
/** Linux O_NONBLOCK status flag on both supported architectures. */
const cargoTokenNonblocking = 0x800;
/** Linux EAGAIN, reported by a declined nonblocking read. */
const cargoTokenAgain = 11;
/** Empty path with AT_EMPTY_PATH selects the descriptor itself. */
const cargoTokenEmptyPath = Memory.allocUtf8String('');
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
 * Reads a descriptor's FIFO identity, or reports a known unrelated descriptor.
 * Unknown metadata remains a failure rather than permission for untracked I/O.
 * @param fd descriptor used by the intercepted call
 * @return identity or failure; null identity denotes known unrelated native I/O
 */
function inspectCargoTokenDescriptor(fd) {
    const data = Memory.alloc(0x100);
    const result = cargoTokenStatx(fd, cargoTokenEmptyPath, 0x1000, 0x101, data);
    if (result.value !== 0) {
        if (result.errno === 9)
            return {identity: null};
        return {failure: 'Descriptor statx failed', nativeErrno: result.errno};
    }
    const mask = data.readU32();
    if ((mask & 1) !== 1)
        return {failure: 'Descriptor type is unavailable'};
    const isFifo = (data.add(0x1c).readU16() & 0xf000) === 0x1000;
    if (isFifo === false)
        return {identity: null};
    if ((mask & 0x100) !== 0x100)
        return {failure: 'FIFO inode is unavailable'};
    return {identity: data.add(0x88).readU32() + ':' + data.add(0x8c).readU32() + ':' +
        data.add(0x20).readU64().toString()};
}

/**
 * Indicates whether a control exactly authorizes this pending one-byte operation.
 * @param message copied Frida control object
 * @param sequence current native request's canonical sequence
 * @param operation read or write requested by the held call
 * @param blocking false only for a read whose descriptor is O_NONBLOCK, which a control without a byte declines
 * @return true only for the exact identities, operation and valid byte shape
 */
function acceptsCargoTokenControl(message, sequence, operation, blocking) {
    if (message === null || typeof message !== 'object' || Array.isArray(message))
        return false;
    const expected = ['type', 'protocol', 'version', 'attempt', 'installation', 'pid', 'sequence', 'operation'];
    const actual = Object.keys(message).sort();
    const declined = operation === 'read' && blocking === false && actual.indexOf('byte') === -1;
    if (operation === 'read' && declined === false)
        expected.push('byte');
    const hasExactFields = JSON.stringify(actual) === JSON.stringify(expected.sort());
    if (hasExactFields === false)
        return false;
    const matchesIdentity = message.type === ('cargo-token-control-' + sequence) &&
        message.protocol === cargoTokenProtocol && message.version === cargoTokenVersion &&
        message.attempt === cargoTokenIdentity.attempt &&
        message.installation === cargoTokenIdentity.installation &&
        message.pid === String(Process.id) && message.sequence === sequence &&
        message.operation === operation;
    if (matchesIdentity === false)
        return false;
    if (operation === 'write' || declined)
        return true;
    return Number.isInteger(message.byte) && message.byte >= 0 && message.byte <= 255;
}

/**
 * Waits for an exact control, preserving the parked operation after rejection.
 * @param sequence pending request identity
 * @param operation intercepted operation
 * @param blocking false only for a read whose descriptor is O_NONBLOCK
 * @return exact accepted control, including the delivered byte for a granted read
 */
function awaitCargoTokenControl(sequence, operation, blocking) {
    let accepted;
    while (accepted === undefined) {
        recv('cargo-token-control-' + sequence, function receiveCargoTokenControl(message) {
            if (acceptsCargoTokenControl(message, sequence, operation, blocking))
                accepted = message;
            else
                sendCargoTokenEvent({event: 'control-rejected', sequence: sequence, operation: operation});
        }).wait();
    }
    return accepted;
}

for (const operation of ['read', 'write']) {
    const address = cargoTokenLibc.getExportByName(operation);
    Interceptor.attach(address, {
        /** Park selected transfers until the controller has completed their exact I/O. */
        onEnter(args) {
            this.cargoTokenMediated = false;
            const savedErrno = this.errno;
            const fd = args[0].toInt32();
            const buffer = args[1];
            const count = args[2].toString();
            const transfersBytes = count !== '0x0';
            const inspection = transfersBytes ? inspectCargoTokenDescriptor(fd) : {identity: null};
            this.errno = savedErrno;
            if (inspection.failure !== undefined) {
                sendCargoTokenEvent({event: 'failure', cause: inspection.failure,
                    operation: operation, sequence: nextCargoTokenSequence()});
                parkCargoTokenFailure();
            }
            if (inspection.identity === cargoTokenIdentity.fifo) {
                const sequence = nextCargoTokenSequence();
                if (count !== '0x1') {
                    sendCargoTokenEvent({event: 'failure', cause: 'Jobserver transfer count differs from one',
                        operation: operation, sequence: sequence});
                    parkCargoTokenFailure();
                }
                let blocking = true;
                if (operation === 'read') {
                    const flags = cargoTokenFcntl(fd, cargoTokenGetFlags);
                    if (flags.value < 0) {
                        sendCargoTokenEvent({event: 'failure', cause: 'Descriptor flags are unavailable',
                            operation: operation, sequence: sequence});
                        parkCargoTokenFailure();
                    }
                    blocking = (flags.value & cargoTokenNonblocking) === 0;
                }
                const request = {event: 'transfer', sequence: sequence, operation: operation};
                if (operation === 'write')
                    request.byte = buffer.readU8();
                else
                    request.blocking = blocking;
                sendCargoTokenEvent(request);
                const reply = awaitCargoTokenControl(sequence, operation, blocking);
                const declined = operation === 'read' && reply.byte === undefined;
                if (operation === 'read' && declined === false)
                    buffer.writeU8(reply.byte);
                args[2] = ptr(0);
                this.cargoTokenMediated = true;
                this.cargoTokenResult = declined ? -1 : 1;
                this.cargoTokenErrno = declined ? cargoTokenAgain : savedErrno;
                this.errno = savedErrno;
            }
        },
        /** Report the completed or declined controller transfer after harmless zero-count native I/O. */
        onLeave(result) {
            if (this.cargoTokenMediated) {
                result.replace(this.cargoTokenResult);
                this.errno = this.cargoTokenErrno;
            }
        }
    });
}
Interceptor.flush();
sendCargoTokenEvent({event: 'ready'});
