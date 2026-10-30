'use strict';
const storage = [];
function vector(values) {
  const result = Memory.alloc((values.length + 1) * Process.pointerSize);
  storage.push(result);
  values.forEach((value, index) => {
    const text = Memory.allocUtf8String(value);
    storage.push(text);
    result.add(index * Process.pointerSize).writePointer(text);
  });
  result.add(values.length * Process.pointerSize).writePointer(NULL);
  return result;
}
const launch = __LAUNCH__;
const path = Memory.allocUtf8String(launch.command[0]);
const args = vector(launch.command);
const environment = vector(Object.entries(launch.environment).map(([key, value]) => key + '=' + value));
const exec = new SystemFunction(Module.getGlobalExportByName('execve'), 'int',
  ['pointer', 'pointer', 'pointer'], {scheduling: 'cooperative', traps: 'default'});
const uid = new NativeFunction(Module.getGlobalExportByName('getuid'), 'uint', []);
const gid = new NativeFunction(Module.getGlobalExportByName('getgid'), 'uint', []);
recv('execute', () => {
  Process.runOnThread(Process.id, () => {
    send('leader-enter:' + Process.id + ':' + Process.getCurrentThreadId() + ':' + uid() + ':' + gid());
    const result = exec(path, args, environment);
    send('exec-returned:' + result.value + ':' + result.errno);
  }).then(() => send('callback-returned')).catch(error => send('dispatch-error:' + error));
});
send('leader-script-ready:' + Process.id);
