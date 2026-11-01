/**
 * Mediates selected named-semaphore waits in one held Windows compiler image.
 * The controller injects windowsCargoTokenIdentity and retains all reservations before granting. This script never
 * decrements the selected semaphore. It polls cancellation and finite deadlines between immediate controller probes;
 * a cancellation racing a definite grant returns that grant before completing the native wait. Unrelated handles
 * keep their original operations. Unsupported selected-pool forms park before their effect and require Job termination.
 * The image must not execute until this script's ready frame is accepted. This bounded API coverage requires the
 * deployed Rust jobserver's native trace to be accepted before production admission.
 */

/** Kernel32 exports forward to the implementation selected by the installed Windows runtime. */
const windowsTokenKernel = Process.getModuleByName('kernel32.dll');
/** Native API options release the JS lock while the genuine kernel call executes. */
const windowsTokenOptions = {scheduling: 'cooperative', traps: 'none'};
/** The invocation pool is opened only for identity comparison, not local count changes. */
const windowsTokenOpen = new SystemFunction(windowsTokenKernel.getExportByName('OpenSemaphoreW'), 'pointer',
    ['uint', 'int', 'pointer'], windowsTokenOptions);
/** Kernel object comparison recognizes aliases without trusting recycled numeric handle values. */
const windowsTokenCompare = new NativeFunction(Process.getModuleByName('kernelbase.dll').getExportByName('CompareObjectHandles'), 'int',
    ['pointer', 'pointer'], windowsTokenOptions);
/** Original wait used only for unrelated handles and cancellation-event polls. */
const windowsTokenWaitAddress = windowsTokenKernel.getExportByName('WaitForSingleObject');
/** Native original preserved by Frida when called from a replacement. */
const windowsTokenWait = new SystemFunction(windowsTokenWaitAddress, 'uint', ['pointer', 'uint'], windowsTokenOptions);
/** Wait-any native entry point used by Rust's cancellation helper. */
const windowsTokenMultipleAddress = windowsTokenKernel.getExportByName('WaitForMultipleObjects');
/** Unrelated wait sets retain their genuine native behavior. */
const windowsTokenMultiple = new SystemFunction(windowsTokenMultipleAddress, 'uint',
    ['uint', 'pointer', 'int', 'uint'], windowsTokenOptions);
/** Original return entry point for unrelated semaphores. */
const windowsTokenReleaseAddress = windowsTokenKernel.getExportByName('ReleaseSemaphore');
/** Original return operation never runs on the selected pool. */
const windowsTokenRelease = new SystemFunction(windowsTokenReleaseAddress, 'int',
    ['pointer', 'int', 'pointer'], windowsTokenOptions);
/** Millisecond monotonic clock used for finite native wait deadlines. */
const windowsTokenTick = new NativeFunction(windowsTokenKernel.getExportByName('GetTickCount64'), 'uint64', [],
    windowsTokenOptions);
/** Native pause between immediate empty replies, allowing peer images to return tokens. */
const windowsTokenSleep = new NativeFunction(windowsTokenKernel.getExportByName('Sleep'), 'void', ['uint'],
    windowsTokenOptions);
/** Same-image explicit reservations, identified by controller-generated UUIDs. */
const windowsTokenGrants = [];
/** Exact per-thread zero-time reservations potentially returned by the deployed available() observation. */
const windowsTokenZeroWaitGrants = new Map();
/** Strict per-installation request sequence represented without floating-point loss. */
let windowsTokenSequence = uint64(0);
/** Retained native functions prevent callback collection while interception is installed. */
const windowsTokenCallbacks = [];
/** Retained pool handle lives in this owned process until complete Job destruction. */
const windowsTokenPool = windowsTokenOpen(0x100002, 0,
    Memory.allocUtf16String(windowsCargoTokenIdentity.semaphore));
if (windowsTokenPool.value.isNull())
    throw new Error('OpenSemaphoreW failed with Windows error ' + windowsTokenPool.lastError);
if (String(Process.id) !== windowsCargoTokenIdentity.pid)
    throw new Error('Windows Cargo token installation PID differs from Process.id');

/**
 * Emits one event with immutable image identity.
 * @param fields event-specific fields
 */
function windowsTokenSend(fields) {
    send(Object.assign({}, fields, {protocol: 'codex-windows-cargo-tokens', version: 1,
        attempt: windowsCargoTokenIdentity.attempt, installation: windowsCargoTokenIdentity.installation,
        pid: String(Process.id)}));
}

/**
 * Parks an unsupported operation without executing its selected-pool effect.
 * @param cause unsupported native form
 */
function windowsTokenFail(cause) {
    windowsTokenSend({event: 'unsupported', sequence: windowsTokenNext(), cause: cause});
    while (true)
        recv('windows-cargo-token-failure', function ignoreFailure() {}).wait();
}

/**
 * Allocates the next exact sequence; exhaustion parks without another effect.
 * @return canonical positive decimal sequence
 */
function windowsTokenNext() {
    if (windowsTokenSequence.compare(uint64('9223372036854775807')) >= 0) {
        send({type: 'windows-cargo-token-sequence-exhausted'});
        while (true)
            recv('windows-cargo-token-failure', function ignoreExhaustion() {}).wait();
    }
    windowsTokenSequence = windowsTokenSequence.add(1);
    return windowsTokenSequence.toString();
}

/**
 * Performs one correlated control exchange, never repeating an uncertain native request.
 * @param operation acquire, return or cancel
 * @param grant definite grant identity for return/cancel, absent for acquire
 * @return exact matching controller response
 */
function windowsTokenExchange(operation, grant) {
    const sequence = windowsTokenNext();
    const event = {event: operation, sequence: sequence};
    if (grant !== undefined)
        event.grant = grant;
    windowsTokenSend(event);
    let accepted;
    while (accepted === undefined) {
        recv('windows-cargo-token-control-' + sequence, function receiveWindowsTokenControl(message) {
            const matches = message.protocol === 'codex-windows-cargo-tokens' && message.version === 1 &&
                message.attempt === windowsCargoTokenIdentity.attempt &&
                message.installation === windowsCargoTokenIdentity.installation &&
                message.pid === String(Process.id) && message.sequence === sequence && message.operation === operation;
            const outcomeValid = operation === 'probe' ?
                (message.outcome === 'available' && Number.isInteger(message.available) && message.available >= 0) :
                operation === 'acquire' ?
                (message.outcome === 'empty' || (message.outcome === 'grant' && typeof message.grant === 'string')) :
                (message.outcome === 'ack' && message.grant === grant);
            const expected = ['type', 'protocol', 'version', 'attempt', 'installation', 'pid', 'sequence', 'operation',
                'outcome'];
            if (operation === 'probe')
                expected.push('available');
            else if (message.outcome !== 'empty')
                expected.push('grant');
            const shapeValid = JSON.stringify(Object.keys(message).sort()) === JSON.stringify(expected.sort());
            if (matches && outcomeValid && shapeValid)
                accepted = message;
            else
                windowsTokenFail('Invalid correlated token control');
        }).wait();
    }
    return accepted;
}

/**
 * Indicates whether a handle refers to the retained invocation semaphore, including aliases.
 * @param handle native caller handle
 * @return true only for the same kernel object
 */
function windowsTokenSelected(handle) {
    return windowsTokenCompare(handle, windowsTokenPool.value) !== 0;
}

/**
 * Observes a genuine cancellation event without consuming pool capacity.
 * @param cancel trusted helper event
 * @return true when signaled
 */
function windowsTokenCancelled(cancel) {
    const result = windowsTokenWait(cancel, 0).value;
    if (result === 0)
        return true;
    if (result === 258)
        return false;
    windowsTokenFail('Cancellation event poll failed');
}

/**
 * Waits for controller capacity while preserving cancellation priority and a finite native deadline.
 * @param timeout native DWORD milliseconds, including zero and INFINITE
 * @param cancel optional helper event at wait-any index zero
 * @return WAIT_OBJECT_0 for a single wait, index one for helper acquisition, zero for cancellation, or WAIT_TIMEOUT
 */
function windowsTokenAcquire(timeout, cancel) {
    const began = windowsTokenTick();
    while (true) {
        if (cancel !== undefined && windowsTokenCancelled(cancel))
            return 0;
        const reply = windowsTokenExchange('acquire');
        if (reply.outcome === 'grant') {
            // A grant can arrive after cancellation. Return that exact reservation before cancellation completes.
            if (cancel !== undefined && windowsTokenCancelled(cancel)) {
                windowsTokenExchange('cancel', reply.grant);
                return 0;
            }
            const expiredGrant = timeout !== 0 && timeout !== 0xffffffff &&
                windowsTokenTick().sub(began).compare(uint64(timeout)) >= 0;
            if (expiredGrant) {
                windowsTokenExchange('cancel', reply.grant);
                return 258;
            }
            windowsTokenGrants.push(reply.grant);
            return cancel === undefined ? 0 : 1;
        }
        const expired = timeout !== 0xffffffff && windowsTokenTick().sub(began).compare(uint64(timeout)) >= 0;
        if (expired)
            return 258;
        windowsTokenSleep(1);
    }
}

windowsTokenCallbacks.push(new NativeCallback(function waitSingle(handle, timeout) {
    if (!windowsTokenSelected(handle)) {
        const original = windowsTokenWait(handle, timeout);
        this.lastError = original.lastError;
        return original.value;
    }
    const thread = Process.getCurrentThreadId();
    windowsTokenZeroWaitGrants.delete(thread);
    const savedError = this.lastError;
    const result = windowsTokenAcquire(timeout);
    if (timeout === 0 && result === 0)
        windowsTokenZeroWaitGrants.set(thread, windowsTokenGrants[windowsTokenGrants.length - 1]);
    this.lastError = savedError;
    return result;
}, 'uint', ['pointer', 'uint']));
Interceptor.replace(windowsTokenWaitAddress, windowsTokenCallbacks[0]);

windowsTokenCallbacks.push(new NativeCallback(function waitMultiple(count, handles, all, timeout) {
    if (count === 0 || count > 64 || handles.isNull()) {
        const original = windowsTokenMultiple(count, handles, all, timeout);
        this.lastError = original.lastError;
        return original.value;
    }
    const selected = [];
    for (let index = 0; index < count; ++index)
        if (windowsTokenSelected(handles.add(index * Process.pointerSize).readPointer()))
            selected.push(index);
    if (selected.length === 0) {
        const original = windowsTokenMultiple(count, handles, all, timeout);
        this.lastError = original.lastError;
        return original.value;
    }
    if (count !== 2 || all || selected.length !== 1 || selected[0] !== 1)
        windowsTokenFail('Selected semaphore wait must be wait-any [cancelEvent,pool]');
    windowsTokenZeroWaitGrants.delete(Process.getCurrentThreadId());
    const savedError = this.lastError;
    const result = windowsTokenAcquire(timeout, handles.readPointer());
    this.lastError = savedError;
    return result;
}, 'uint', ['uint', 'pointer', 'int', 'uint']));
Interceptor.replace(windowsTokenMultipleAddress, windowsTokenCallbacks[1]);

windowsTokenCallbacks.push(new NativeCallback(function releaseSemaphore(handle, count, previous) {
    if (!windowsTokenSelected(handle)) {
        const original = windowsTokenRelease(handle, count, previous);
        this.lastError = original.lastError;
        return original.value;
    }
    if (count !== 1)
        windowsTokenFail('Selected semaphore return requires count one');
    const thread = Process.getCurrentThreadId();
    const savedError = this.lastError;
    let grant;
    if (!previous.isNull()) {
        // Deployed jobserver 0.1.34 available(): WaitForSingleObject(pool,0), then ReleaseSemaphore(1,&count).
        // Observe the previous count while our exact reservation remains held, then return it before native success.
        // There is no credit for the next wait: it must perform a fresh controller acquisition.
        grant = windowsTokenZeroWaitGrants.get(thread);
        if (grant === undefined || !windowsTokenGrants.includes(grant))
            windowsTokenFail('Availability observation has no matching zero-time thread reservation');
        const reply = windowsTokenExchange('probe');
        windowsTokenExchange('return', grant);
        previous.writeS32(reply.available);
    } else {
        if (windowsTokenGrants.length === 0)
            windowsTokenFail('Selected semaphore return has no definite image reservation');
        grant = windowsTokenGrants[windowsTokenGrants.length - 1];
        windowsTokenExchange('return', grant);
    }
    windowsTokenGrants.splice(windowsTokenGrants.indexOf(grant), 1);
    for (const [owner, retained] of windowsTokenZeroWaitGrants)
        if (retained === grant)
            windowsTokenZeroWaitGrants.delete(owner);
    this.lastError = savedError;
    return 1;
}, 'int', ['pointer', 'int', 'pointer']));
Interceptor.replace(windowsTokenReleaseAddress, windowsTokenCallbacks[2]);

// Extended waits are outside the pinned initial contract: stop selected-pool forms before any native decrement.
for (const name of ['WaitForSingleObjectEx', 'WaitForMultipleObjectsEx']) {
    Interceptor.attach(windowsTokenKernel.getExportByName(name), {
        /** Rejects a selected semaphore entering an unsupported extended wait. */
        onEnter(args) {
            if (name === 'WaitForSingleObjectEx') {
                if (windowsTokenSelected(args[0]))
                    windowsTokenFail('Selected pool extended wait is unsupported');
            } else {
                const count = args[0].toUInt32();
                for (let index = 0; index < count; ++index)
                    if (windowsTokenSelected(args[1].add(index * Process.pointerSize).readPointer()))
                        windowsTokenFail('Selected pool extended wait set is unsupported');
            }
        }
    });
}
Interceptor.flush();
windowsTokenSend({event: 'ready'});
