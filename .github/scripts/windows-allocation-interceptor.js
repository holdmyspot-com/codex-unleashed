/* Frida diagnostic: hold one configured thread's denied VirtualAlloc inside its
 * native call, then retry only after a controller 'retry' message. All other
 * requests retain their original result and Windows last-error value. */
const allocationAddress = Process.getModuleByName('kernel32.dll').getExportByName('VirtualAlloc');
const allocationSignature = ['pointer', 'size_t', 'uint', 'uint'];
const originalAllocate = new SystemFunction(allocationAddress, 'pointer', allocationSignature);

// The controller arms the hook after injection and setting the worker's limit.
let configuration = null;

rpc.exports = {
  arm(threadId, bytes) {
    configuration = { threadId, bytes };
    return configuration;
  },
  // Capture native wait locations after the unchanged worker-exit check times out.
  inspectThreads() {
    return Process.enumerateThreads().map(thread => ({
      id: thread.id,
      state: thread.state,
      frames: Thread.backtrace(thread.context, Backtracer.ACCURATE).slice(0, 16).map(address => {
        const module = Process.findModuleByAddress(address);
        return { symbol: DebugSymbol.fromAddress(address).toString(),
          module: module === null ? null : module.name,
          offset: module === null ? null : address.sub(module.base).toString() };
      })
    }));
  },
  // Isolate whether leaving this hook active prevents native process shutdown.
  removeHook() {
    Interceptor.revert(allocationAddress);
    return true;
  }
};

Interceptor.replace(allocationAddress, new NativeCallback(function (address, bytes, type, protection) {
  const result = originalAllocate(address, bytes, type, protection);
  const selectedRequest = configuration !== null &&
    this.threadId === configuration.threadId && Number(bytes) === configuration.bytes;
  if (!selectedRequest) {
    this.lastError = result.lastError;
    return result.value;
  }

  if (!result.value.isNull()) {
    send({ event: 'unexpected_native_success', pid: Process.id, pointer: result.value.toString() });
    this.lastError = result.lastError;
    return result.value;
  }

  send({ event: 'native_allocation_blocked', pid: Process.id, thread_id: this.threadId,
    bytes: Number(bytes), windows_error: result.lastError, address_hint: address.toString(),
    allocation_type: type, protection });
  recv('retry', function () {}).wait();
  const retried = originalAllocate(address, bytes, type, protection);
  send({ event: 'native_allocation_retried', pid: Process.id, thread_id: this.threadId,
    bytes: Number(bytes), succeeded: !retried.value.isNull(), pointer: retried.value.toString(),
    windows_error: retried.lastError });
  this.lastError = retried.lastError;
  return retried.value;
}, 'pointer', allocationSignature));

send({ event: 'hook_installed', pid: Process.id, address: allocationAddress.toString() });
