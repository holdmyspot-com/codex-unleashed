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
const open = new SystemFunction(Module.getGlobalExportByName('open'), 'int', ['pointer', 'int'],
  {scheduling: 'cooperative', traps: 'default'});
const fcntl = new SystemFunction(Module.getGlobalExportByName('fcntl'), 'int', ['int', 'int', 'int'],
  {scheduling: 'cooperative', traps: 'default'});
const close = new SystemFunction(Module.getGlobalExportByName('close'), 'int', ['int'],
  {scheduling: 'cooperative', traps: 'default'});
/**
 * Opens the jobserver FIFO, inheritable, on each exact descriptor number that the make flags name.
 * GNU Make before 4.4 accepts only these numbers. An occupied number fails rather than being replaced.
 * @return null on success, or the failed step and errno
 */
function installJobserver() {
  if (launch.jobserver === undefined)
    return null;
  // O_RDWR keeps the FIFO open without a peer; a descriptor from open() carries no close-on-exec flag.
  const opened = open(Memory.allocUtf8String(launch.jobserver.fifo), 2);
  if (opened.value < 0)
    return 'open:' + opened.errno;
  try {
    for (const target of [launch.jobserver.read, launch.jobserver.write]) {
      // F_GETFD fails with EBADF only when the number is free.
      const existing = fcntl(target, 1, 0);
      if (existing.value !== -1 || existing.errno !== 9)
        return 'occupied:' + target;
      // F_DUPFD returns the lowest free number at or above the target, which is the target itself when it is free.
      const duplicate = fcntl(opened.value, 0, target);
      if (duplicate.value !== target) {
        if (duplicate.value >= 0)
          close(duplicate.value);
        return 'dup:' + target + ':' + duplicate.value + ':' + duplicate.errno;
      }
    }
    return null;
  } finally {
    close(opened.value);
  }
}
const uid = new NativeFunction(Module.getGlobalExportByName('getuid'), 'uint', []);
const gid = new NativeFunction(Module.getGlobalExportByName('getgid'), 'uint', []);
recv('execute', () => {
  Process.runOnThread(Process.id, () => {
    const jobserverFailure = installJobserver();
    if (jobserverFailure !== null) {
      send('jobserver-failed:' + jobserverFailure);
      return;
    }
    send('leader-enter:' + Process.id + ':' + Process.getCurrentThreadId() + ':' + uid() + ':' + gid());
    const result = exec(path, args, environment);
    send('exec-returned:' + result.value + ':' + result.errno);
  }).then(() => send('callback-returned')).catch(error => send('dispatch-error:' + error));
});
send('leader-script-ready:' + Process.id);
