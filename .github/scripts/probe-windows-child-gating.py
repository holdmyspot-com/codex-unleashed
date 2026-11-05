"""Check whether Frida child gating stalls a Git for Windows shell started outside any repository.

A Cargo build script (curl-sys) runs `git submodule update --init curl` from its registry source directory. Under the
release scheduler on Windows ARM64, the `sh` that runs git-submodule stayed idle for hours with no children. This probe
runs the same command from a new empty directory outside any repository in three modes:

- `off`: no instrumentation; the command must exit within the bound (expected: nonzero, "not a git repository").
- `gating`: Frida spawns the command, enables child gating in every image, and resumes each gated child.
- `script`: as `gating`, and also loads an empty script into every image, as the scheduler loads its hooks.
- `root`: Frida spawns the command and enables child gating in it alone; each gated child is resumed without being
  attached, so the Git for Windows shells it starts run uninstrumented.
- `attach`: as `root`, but each gated child is attached (Frida's agent is injected) before it resumes, without enabling
  child gating in it; the shells' own fork children start uninstrumented.

On a hang, every attached process's threads are listed with symbolized backtraces, and the command's process tree is
printed, before the tree is ended.

Each mode prints one JSON line per event and exits 1 when the command does not exit within the bound.
"""
import argparse
import ctypes
import threading
import json
import os
import queue
import shutil
import subprocess
import sys
import tempfile
import time

SYNCHRONIZE = 0x00100000
PROCESS_QUERY_LIMITED_INFORMATION = 0x1000
WAIT_OBJECT_0 = 0


def emit(event, **fields):
    print(json.dumps({"event": event, "time": time.time(), **fields}), flush=True)


def wait_for_exit(pid, seconds):
    """Returns the exit code of the process, or None when it is still running after the bound."""
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel.OpenProcess.restype = ctypes.c_void_p
    handle = kernel.OpenProcess(SYNCHRONIZE | PROCESS_QUERY_LIMITED_INFORMATION, False, pid)
    if not handle:
        raise OSError(ctypes.get_last_error(), "OpenProcess failed")
    try:
        if kernel.WaitForSingleObject(ctypes.c_void_p(handle), int(seconds * 1000)) != WAIT_OBJECT_0:
            return None
        code = ctypes.c_ulong()
        if not kernel.GetExitCodeProcess(ctypes.c_void_p(handle), ctypes.byref(code)):
            raise OSError(ctypes.get_last_error(), "GetExitCodeProcess failed")
        return code.value
    finally:
        kernel.CloseHandle(ctypes.c_void_p(handle))


def run_uninstrumented(command, directory, bound):
    try:
        result = subprocess.run(command, cwd=directory, stdin=subprocess.DEVNULL, capture_output=True, text=True,
                                timeout=bound)
    except subprocess.TimeoutExpired:
        emit("command_hung", mode="off", bound_seconds=bound)
        return False
    emit("command_exited", mode="off", exit_code=result.returncode, stderr=result.stderr[-2000:])
    return True


def run_instrumented(command, directory, bound, mode):
    import frida

    load_script = mode == "script"
    device = frida.get_local_device()
    children = queue.Queue()
    device.on("child-added", lambda child: children.put(child))
    sessions = []
    scripts = []

    def instrument(pid, gate=True):
        session = device.attach(pid)
        if gate:
            session.enable_child_gating()
        sessions.append((pid, session))
        if load_script:
            script = session.create_script("void 0;")
            script.load()
            scripts.append(script)

    root = device.spawn(command, cwd=directory)
    instrument(root)
    device.resume(root)
    emit("root_resumed", mode=mode, pid=root, frida_version=frida.__version__)

    deadline = time.monotonic() + bound
    while time.monotonic() < deadline:
        code = wait_for_exit(root, 0.2)
        if code is not None:
            emit("command_exited", mode=mode, exit_code=code, gated_children=len(sessions) - 1)
            return True
        try:
            child = children.get_nowait()
        except queue.Empty:
            continue
        emit("child_added", mode=mode, pid=child.pid, parent_pid=child.parent_pid, origin=child.origin,
             path=child.path)
        try:
            if mode != "root":
                instrument(child.pid, gate=mode != "attach")
            device.resume(child.pid)
            emit("child_resumed", mode=mode, pid=child.pid)
        except frida.ProcessNotFoundError as error:
            emit("child_gone", mode=mode, pid=child.pid, error=str(error))

    emit("command_hung", mode=mode, bound_seconds=bound, gated_children=len(sessions) - 1)
    pending = []
    while not children.empty():
        pending.append(children.get_nowait().pid)
    emit("hung_state", mode=mode, pending_children=pending)
    emit_process_tree(root)
    for pid, session in sessions:
        emit_threads(pid, session)
    # A gated child that is left running keeps the job's output pipe open, so end the whole tree.
    for pid in [root] + pending:
        subprocess.run(["taskkill", "/T", "/F", "/PID", str(pid)], capture_output=True)
    return False


THREAD_DUMP = """
const threads = [];
for (const thread of Process.enumerateThreads()) {
  let frames;
  try {
    frames = Thread.backtrace(thread.context, Backtracer.ACCURATE);
    if (frames.length === 0) frames = Thread.backtrace(thread.context, Backtracer.FUZZY);
    frames = frames.slice(0, 32).map(address => DebugSymbol.fromAddress(address).toString());
  } catch (error) {
    frames = ['backtrace failed: ' + error];
  }
  threads.push({ id: thread.id, state: thread.state, pc: DebugSymbol.fromAddress(thread.context.pc).toString(), frames });
}
send({ arch: Process.arch, modules: Process.enumerateModules().map(module => module.name), threads });
"""


def emit_threads(pid, session):
    """Prints one hung process's threads; a wedged agent is reported instead of blocking the probe."""
    result = queue.Queue()

    def dump():
        try:
            script = session.create_script(THREAD_DUMP)
            script.on("message", lambda message, data: result.put(message))
            script.load()
        except Exception as error:
            result.put({"type": "error", "description": repr(error)})

    worker = threading.Thread(target=dump, daemon=True)
    worker.start()
    try:
        message = result.get(timeout=30)
    except queue.Empty:
        emit("threads_unavailable", pid=pid, reason="no thread dump within 30 seconds")
        return
    if message.get("type") == "send":
        emit("threads", pid=pid, **message["payload"])
    else:
        emit("threads_unavailable", pid=pid, reason=message.get("description"), stack=message.get("stack"))


def emit_process_tree(root):
    """Prints the live descendants of the hung command with their images and command lines."""
    listing = subprocess.run(["powershell", "-NoProfile", "-Command",
                              "Get-CimInstance Win32_Process | Select-Object ProcessId,ParentProcessId,Name,"
                              "ExecutablePath,CommandLine | ConvertTo-Json -Compress"],
                             capture_output=True, text=True, timeout=60)
    processes = json.loads(listing.stdout)
    children = {}
    for process in processes:
        children.setdefault(process["ParentProcessId"], []).append(process)
    pending = [root]
    tree = [process for process in processes if process["ProcessId"] == root]
    while pending:
        for process in children.get(pending.pop(), []):
            tree.append(process)
            pending.append(process["ProcessId"])
    emit("process_tree", root=root, processes=tree)


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--mode", choices=("off", "gating", "script", "root", "attach"), required=True)
    parser.add_argument("--bound", type=float, default=120.0, help="Seconds the command may run before it counts as hung")
    arguments = parser.parse_args()

    # Frida's spawn does not search PATH, so every mode starts the same resolved executable.
    command = [shutil.which("git"), "submodule", "update", "--init", "curl"]
    with tempfile.TemporaryDirectory(prefix="child-gating-") as directory:
        probe = subprocess.run(["git", "-C", directory, "rev-parse", "--git-dir"], capture_output=True, text=True)
        emit("directory_outside_repository", path=directory, rev_parse_exit=probe.returncode)
        if arguments.mode == "off":
            exited = run_uninstrumented(command, directory, arguments.bound)
        else:
            exited = run_instrumented(command, directory, arguments.bound, arguments.mode)
    sys.exit(0 if exited else 1)


if __name__ == "__main__":
    main()
