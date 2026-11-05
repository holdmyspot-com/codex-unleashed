"""Run a build command and explain it, then stop it, when its logs stay quiet.

The release build writes every stage's output under one evidence directory. When no file there changes for
--first-report seconds, other than by appending the scheduler's own stall reports, this writes a report of every user
process to --report: its state, kernel wait channel, current system call, tracer and kernel stacks, plus a Java and a
mixed Java and native thread dump of each Java process.
A host without /proc, such as macOS, gets each process's ps line instead, and each JVM's thread dump and native sample.
Windows gets each process with its count of suspended threads, and each JVM's thread dump.
Zombies are listed with their name, since they have no command line. It repeats every --interval seconds
while the build stays quiet, and once the build has been quiet for --stop-after seconds it terminates the command's
process group, or on Windows its process tree, and exits 124. Otherwise it exits with the command's own status.

Reading other users' kernel stacks and attaching to their JVMs needs root, so the report is produced by rerunning this
script with --dump under non-interactive sudo when that is available, and as the current user otherwise.
"""
import argparse
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import time

TASK_STACK_LIMIT = 16
"""Most threads whose kernel stacks are printed for one process; every thread in state D, and every thread of a Java
process, is printed regardless."""


def read(path):
    """Returns a /proc file's text, or a short reason when it cannot be read."""
    try:
        return Path(path).read_text(errors="replace")
    except OSError as failure:
        return f"<{failure.strerror or type(failure).__name__}>"


def status_fields(pid):
    """Returns the State, PPid, TracerPid and Uid fields of one process."""
    fields = {}
    for line in read(f"/proc/{pid}/status").splitlines():
        key, _, value = line.partition(":")
        if key in ("State", "PPid", "TracerPid", "Uid"):
            fields[key] = value.strip()
    return fields


def java_dump(uid, command, timeout):
    """Returns the output of one JVM diagnostic command run as the JVM's own user, or why it produced none."""
    if os.name != "nt" and os.geteuid() == 0 and uid != 0:
        command = ["sudo", "-n", f"-u#{uid}", *command]
    try:
        result = subprocess.run(command, capture_output=True, text=True, timeout=timeout)
    except (OSError, subprocess.TimeoutExpired) as failure:
        return f"<{command[0]} failed: {failure}>\n"
    return result.stdout + result.stderr


def process_name(pid, fields):
    """Returns a process's command line, a zombie's bracketed name, or None for a kernel thread or a vanished process.

    A zombie has an empty command line, so it is named from its comm field, as ps does."""
    cmdline = read(f"/proc/{pid}/cmdline").replace("\0", " ").strip()
    if cmdline and not cmdline.startswith("<"):
        return cmdline
    if fields.get("State", "").startswith("Z"):
        return f"[{read(f'/proc/{pid}/comm').strip()}] <defunct>"
    return None


def descriptor_count(pid):
    """Returns how many descriptors one process holds open, or ? when they cannot be listed."""
    try:
        return len(os.listdir(f"/proc/{pid}/fd"))
    except OSError:
        return "?"


def descriptor_limit(pid):
    """Returns one process's soft open-file limit, or ? when its limits cannot be read."""
    for line in read(f"/proc/{pid}/limits").splitlines():
        if line.startswith("Max open files"):
            return line.split()[3]
    return "?"


def sampled(state, command):
    """Indicates whether a process's native threads belong in the report.

    JVMs are sampled for where each thread blocks; a stopped process and a Frida helper are sampled too, because a
    build can stall while one holds the other."""
    name = os.path.basename(command.split(" ")[0])
    return name in ("java", "frida-helper") or state.startswith("T")


def sampled_processes(lines):
    """Returns the split ps lines of the processes whose native threads belong in the report: those sampled selects,
    and every child of a stopped process, because a held child that never answers its instrumentation keeps its stopped
    parent waiting and may itself be running."""
    processes = [fields for fields in (line.split(None, 8) for line in lines) if len(fields) == 9]
    stopped = {fields[0] for fields in processes if fields[3].startswith("T")}
    return [fields for fields in processes if sampled(fields[3], fields[8]) or fields[1] in stopped]


BSD_STATUSES = {1: "SIDL", 2: "SRUN", 3: "SSLEEP", 4: "SSTOP", 5: "SZOMB"}
"""Names of the process states in Darwin's <sys/proc.h>; SSTOP is a stop by signal, such as job control."""

MACH_RUN_STATES = {1: "running", 2: "stopped", 3: "waiting", 4: "uninterruptible", 5: "halted"}
"""Names of the TH_STATE_* thread run states in Darwin's <mach/thread_info.h>."""


def describe_stop(status, task_suspend_count, threads):
    """Returns one line telling a signal stop from a Mach suspension, since Darwin's ps prints T for both.

    status is the BSD process status, task_suspend_count the task's suspend count and threads one (run state, suspend
    count) pair per thread; either of the last two is None when the task port could not be read."""
    names = "?" if threads is None else ",".join(f"{MACH_RUN_STATES.get(state, state)}/{count}"
                                                 for state, count in threads)
    suspended = "?" if task_suspend_count is None else task_suspend_count
    return f"stop: bsd_status={BSD_STATUSES.get(status, status)} task_suspend_count={suspended} threads={names}"


DARWIN_SIGNALS = {1: "SIGHUP", 2: "SIGINT", 3: "SIGQUIT", 4: "SIGILL", 5: "SIGTRAP", 6: "SIGABRT", 7: "SIGEMT",
                  8: "SIGFPE", 9: "SIGKILL", 10: "SIGBUS", 11: "SIGSEGV", 12: "SIGSYS", 13: "SIGPIPE", 14: "SIGALRM",
                  15: "SIGTERM", 16: "SIGURG", 17: "SIGSTOP", 18: "SIGTSTP", 19: "SIGCONT", 20: "SIGCHLD",
                  21: "SIGTTIN", 22: "SIGTTOU", 23: "SIGIO", 24: "SIGXCPU", 25: "SIGXFSZ", 26: "SIGVTALRM",
                  27: "SIGPROF", 28: "SIGWINCH", 29: "SIGINFO", 30: "SIGUSR1", 31: "SIGUSR2"}
"""Names of the signal numbers in Darwin's <sys/signal.h>, which differ from Linux's."""


def describe_signals(traced, stop_signal, pending):
    """Returns one line telling whether a stopped process is traced, which signal stopped it and which are pending.

    traced is whether the process has P_TRACED, stop_signal its p_xstat and pending its p_siglist bit mask, where bit
    n - 1 stands for signal n; each is None when the kernel's process record could not be read."""
    trace = "?" if traced is None else ("yes" if traced else "no")
    stop = "?" if stop_signal is None else DARWIN_SIGNALS.get(stop_signal, stop_signal)
    if pending is None:
        waiting = "?"
    else:
        names = sorted(DARWIN_SIGNALS.get(number, str(number)) for number in range(1, 33)
                       if pending & (1 << (number - 1)))
        waiting = ",".join(names) or "none"
    return f"signals: traced={trace} stop_signal={stop} pending={waiting}"


def darwin_signal_facts(pid):
    """Returns the arguments of describe_signals for one macOS process, read from its kinfo_proc record."""
    import ctypes
    system = ctypes.CDLL(None)
    # CTL_KERN, KERN_PROC, KERN_PROC_PID; struct kinfo_proc is 648 bytes on 64-bit Darwin and starts with struct
    # extern_proc, whose p_flag, p_stat, p_pid, p_siglist and p_xstat lie at offsets 32, 36, 40, 208 and 280.
    name = (ctypes.c_int * 4)(1, 14, 1, pid)
    record = ctypes.create_string_buffer(648)
    size = ctypes.c_size_t(len(record))
    if system.sysctl(name, 4, record, ctypes.byref(size), None, ctypes.c_size_t(0)) != 0 or size.value < 282:
        return None, None, None
    field = lambda offset, width: int.from_bytes(record.raw[offset:offset + width], sys.byteorder)
    if field(40, 4) != pid:
        return None, None, None
    # P_TRACED is 0x800 in <sys/proc.h>.
    return bool(field(32, 4) & 0x800), field(280, 2), field(208, 4)


def darwin_stop_facts(pid):
    """Returns the arguments of describe_stop for one macOS process; the Mach facts need root's access to its task."""
    import ctypes
    system = ctypes.CDLL(None)
    system.proc_pidinfo.argtypes = [ctypes.c_int, ctypes.c_int, ctypes.c_uint64, ctypes.c_void_p, ctypes.c_int]
    system.vm_deallocate.argtypes = [ctypes.c_uint, ctypes.c_size_t, ctypes.c_size_t]
    # PROC_PIDTBSDINFO fills struct proc_bsdinfo, whose second 32-bit field is pbi_status.
    bsd_info = ctypes.create_string_buffer(136)
    status = None
    if system.proc_pidinfo(pid, 3, 0, bsd_info, len(bsd_info)) >= 8:
        status = int.from_bytes(bsd_info.raw[4:8], sys.byteorder)
    own_task = ctypes.c_uint.in_dll(system, "mach_task_self_")
    task = ctypes.c_uint(0)
    if system.task_for_pid(own_task, pid, ctypes.byref(task)) != 0:
        return status, None, None
    try:
        # MACH_TASK_BASIC_INFO (20) fills struct mach_task_basic_info: twelve 32-bit words ending in suspend_count.
        task_info = (ctypes.c_int * 12)()
        count = ctypes.c_uint(12)
        task_suspend_count = task_info[11] if system.task_info(task, 20, task_info, ctypes.byref(count)) == 0 else None
        thread_list = ctypes.POINTER(ctypes.c_uint)()
        thread_count = ctypes.c_uint(0)
        if system.task_threads(task, ctypes.byref(thread_list), ctypes.byref(thread_count)) != 0:
            return status, task_suspend_count, None
        threads = []
        for index in range(thread_count.value):
            # THREAD_BASIC_INFO (3) fills struct thread_basic_info: run_state is word 6 and suspend_count word 8.
            thread_info = (ctypes.c_int * 10)()
            count = ctypes.c_uint(10)
            if system.thread_info(thread_list[index], 3, thread_info, ctypes.byref(count)) == 0:
                threads.append((thread_info[6], thread_info[8]))
            system.mach_port_deallocate(own_task, thread_list[index])
        system.vm_deallocate(own_task, ctypes.cast(thread_list, ctypes.c_void_p).value,
                             thread_count.value * ctypes.sizeof(ctypes.c_uint))
        return status, task_suspend_count, threads
    finally:
        system.mach_port_deallocate(own_task, task)


def dump_without_procfs(jcmd, out):
    """Writes ps's line for every process, then a thread dump of each Java process and a native sample of each process
    that sampled_processes selects.

    This serves hosts without a process file system, such as macOS, where kernel stacks are not readable; sample, when
    present, shows where each thread is blocked in native code. The process group, session and terminal columns show
    whether a stopped process was stopped by terminal job control."""
    try:
        listing = subprocess.run(["ps", "-axww", "-o", "pid=,ppid=,uid=,state=,pgid=,sess=,tt=,wchan=,command="],
                                 capture_output=True, text=True, timeout=60)
    except (OSError, subprocess.TimeoutExpired) as failure:
        out.write(f"<ps failed: {failure}>\n")
        return
    out.write(listing.stdout + listing.stderr)
    sample = Path("/usr/bin/sample")
    for fields in sampled_processes(listing.stdout.splitlines()):
        pid, uid = fields[0], int(fields[2])
        out.write(f"--- pid={pid} state={fields[3]} cmd={fields[8][:200]}\n")
        if sys.platform == "darwin" and fields[3].startswith("T"):
            try:
                out.write(describe_stop(*darwin_stop_facts(int(pid))) + "\n")
                out.write(describe_signals(*darwin_signal_facts(int(pid))) + "\n")
            except (OSError, AttributeError, ValueError) as failure:
                out.write(f"<stop facts failed: {failure}>\n")
        if jcmd and os.path.basename(fields[8].split(" ")[0]) == "java":
            out.write(java_dump(uid, [jcmd, pid, "Thread.print", "-l"], 30))
        if sample.is_file():
            out.write(java_dump(0, [str(sample), pid, "3"], 120))


WINDOWS_PROCESS_QUERY = r"""
$threads = Get-CimInstance Win32_Thread | Group-Object ProcessHandle -AsHashTable -AsString
Get-CimInstance Win32_Process | ForEach-Object {
  $own = if ($threads) { @($threads[[string]$_.ProcessId]) } else { @() }
  $own = @($own | Where-Object { $_ })
  [pscustomobject]@{ pid = $_.ProcessId; ppid = $_.ParentProcessId; name = $_.Name; command = $_.CommandLine;
    threads = $own.Count; suspended = @($own | Where-Object { $_.ThreadWaitReason -eq 5 }).Count }
} | ConvertTo-Json -Compress
"""
"""Lists every process as JSON with its parent, command line, thread count and count of threads whose wait reason is
Suspended (5), which is how a process created suspended, or held by an injector, appears."""


def windows_processes(text):
    """Returns the process records that WINDOWS_PROCESS_QUERY printed; ConvertTo-Json prints a lone record unwrapped."""
    records = json.loads(text)
    return records if isinstance(records, list) else [records]


def dump_windows(processes, out, thread_dump):
    """Writes one line per process, marking one whose every thread is suspended as held, then each JVM's thread dump.

    thread_dump returns the text of one JVM's thread dump, given its process ID."""
    for record in processes:
        threads, suspended = record["threads"], record["suspended"]
        held = "yes" if threads > 0 and suspended == threads else "no"
        command = (record["command"] or record["name"] or "?").replace("\r", " ").replace("\n", " ")
        out.write(f"pid={record['pid']} ppid={record['ppid']} threads={threads} suspended={suspended} held={held} "
                  f"cmd={command[:400]}\n")
    for record in processes:
        if (record["name"] or "").lower() == "java.exe":
            out.write(f"--- pid={record['pid']} {record['name']}\n")
            out.write(thread_dump(record["pid"]))


def dump_windows_host(jcmd, out):
    """Writes the Windows process report, or why PowerShell produced none."""
    try:
        result = subprocess.run(["powershell", "-NoProfile", "-NonInteractive", "-Command", WINDOWS_PROCESS_QUERY],
                                capture_output=True, text=True, timeout=300)
        processes = windows_processes(result.stdout)
    except (OSError, subprocess.TimeoutExpired, ValueError) as failure:
        out.write(f"<process query failed: {failure}>\n")
        return
    dump_windows(processes, out,
                 lambda pid: java_dump(0, [jcmd, str(pid), "Thread.print", "-l"], 30) if jcmd else "<no jcmd>\n")


def dump(jcmd, out, procfs=Path("/proc")):
    """Writes one line per user process, then its threads' kernel stacks and any JVM thread dump.

    Without a process file system at procfs, it writes the portable report of dump_without_procfs instead, and on
    Windows that of dump_windows_host."""
    if os.name == "nt":
        dump_windows_host(jcmd, out)
        out.flush()
        return
    if not procfs.is_dir():
        dump_without_procfs(jcmd, out)
        out.flush()
        return
    pids = sorted(int(name) for name in os.listdir("/proc") if name.isdigit())
    for pid in pids:
        fields = status_fields(pid)
        cmdline = process_name(pid, fields)
        if cmdline is None:
            continue  # kernel threads have no command line; a process that was reaped is gone
        state = fields.get("State", "?").split(" ")[0]
        uid = fields.get("Uid", "-1").split()[0]
        wchan = read(f"/proc/{pid}/wchan").strip() or "0"
        syscall = read(f"/proc/{pid}/syscall").split(" ")[0].strip()
        # The tracer names the process that must resume a tracee held in a ptrace stop. The descriptor count and
        # its soft limit show a process that leaks descriptors before it reaches the limit.
        out.write(f"pid={pid} ppid={fields.get('PPid', '?')} tracer={fields.get('TracerPid', '?')} uid={uid} "
                  f"state={state} wchan={wchan} "
                  f"syscall={syscall} fds={descriptor_count(pid)} nofile={descriptor_limit(pid)} "
                  f"cmd={cmdline[:400]}\n")
    for pid in pids:
        fields = status_fields(pid)
        cmdline = process_name(pid, fields)
        if cmdline is None or fields.get("State", "").startswith("Z"):
            continue  # a zombie has no threads left to inspect
        java = os.path.basename(cmdline.split(" ")[0]) == "java"
        try:
            tasks = sorted(int(name) for name in os.listdir(f"/proc/{pid}/task"))
        except OSError:
            continue
        out.write(f"--- pid={pid} threads={len(tasks)} cmd={cmdline[:200]}\n")
        for index, task in enumerate(tasks):
            task_fields = status_fields(f"{pid}/task/{task}")
            state = task_fields.get("State", "?").split(" ")[0]
            if index >= TASK_STACK_LIMIT and state != "D" and not java:
                continue
            comm = read(f"/proc/{pid}/task/{task}/comm").strip()
            out.write(f"task={task} comm={comm} state={state} tracer={task_fields.get('TracerPid', '?')} "
                      f"wchan={read(f'/proc/{pid}/task/{task}/wchan').strip()}\n")
            out.write(read(f"/proc/{pid}/task/{task}/stack"))
        if jcmd and java:
            uid = int(fields.get("Uid", "-1").split()[0])
            out.write(java_dump(uid, [jcmd, str(pid), "Thread.print", "-l"], 30))
            jhsdb = Path(jcmd).with_name("jhsdb")
            if jhsdb.is_file():
                # Thread.print shows only Java frames; a thread blocked inside a native call needs its native frames.
                out.write(f"--- jhsdb jstack --mixed pid={pid}\n")
                out.write(java_dump(uid, [str(jhsdb), "jstack", "--mixed", "--pid", str(pid)], 300))
    out.flush()


STALL_EVENT = "scheduler_stall"
"""Event of the scheduler's own stall report: a JSON line that the scheduler appends to the build log while no build
work is progressing, so it is not build output."""


class BuildOutput:
    """Tracks when the build last wrote output under the watched directory, other than this watcher's report and the
    scheduler's stall reports."""

    def __init__(self, directory, report):
        self.directory = directory
        self.excluded = os.path.abspath(report)
        self.files = {}
        """For each file seen: its modification time, the size up to which its bytes were classified, and the
        modification time of its last build output."""

    def latest(self):
        """Returns the newest modification time of any file's build output."""
        newest = 0.0
        for root, _, names in os.walk(self.directory):
            for name in names:
                path = os.path.abspath(os.path.join(root, name))
                if path == self.excluded:
                    continue
                try:
                    newest = max(newest, self.observe(path))
                except OSError:
                    pass
        return newest

    def observe(self, path):
        """Classifies one file's change since the previous call and returns the time of its last build output.

        A file seen for the first time, or changed without growing, counts as output. A file that only grew counts
        as output unless every complete line appended since the previous call is a scheduler stall report; a trailing
        partial line is left to classify once it is complete."""
        status = os.stat(path)
        previous = self.files.get(path)
        if previous is None:
            self.files[path] = (status.st_mtime, status.st_size, status.st_mtime)
            return status.st_mtime
        modified, classified, output = previous
        if status.st_mtime == modified:
            return output
        if status.st_size <= classified:
            self.files[path] = (status.st_mtime, status.st_size, status.st_mtime)
            return status.st_mtime
        with open(path, "rb") as stream:
            stream.seek(classified)
            appended = stream.read(status.st_size - classified)
        complete = appended.rfind(b"\n") + 1
        lines = appended[:complete].splitlines()
        partial = appended[complete:]
        if all(stall_report(line) for line in lines) and (not partial or partial.startswith(b"{")):
            self.files[path] = (status.st_mtime, classified + complete, output)
            return output
        self.files[path] = (status.st_mtime, status.st_size, status.st_mtime)
        return status.st_mtime


def stall_report(line):
    """Returns whether one log line is the scheduler's stall report."""
    try:
        record = json.loads(line)
    except ValueError:
        return False
    return isinstance(record, dict) and record.get("event") == STALL_EVENT


def write_report(report, quiet, jcmd):
    """Appends one process report, as root when non-interactive sudo is available."""
    with open(report, "a") as out:
        out.write(f"=== quiet_seconds={quiet:.0f} time={time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime())}\n")
        out.flush()
        command = [sys.executable, "-I", os.path.abspath(__file__), "--dump"]
        if jcmd:
            command += ["--jcmd", jcmd]
        if os.name != "nt" and os.geteuid() != 0 and subprocess.run(["sudo", "-n", "true"], capture_output=True).returncode == 0:
            command = ["sudo", "-n", *command]
        try:
            subprocess.run(command, stdout=out, stderr=out, timeout=600)
        except subprocess.TimeoutExpired:
            out.write("<process report timed out>\n")


def signal_group(pgid, sig):
    """Signals one process group, as root when this user may not signal every remaining member.

    Raises ProcessLookupError when the group is gone and PermissionError when its members still cannot be signaled."""
    try:
        os.killpg(pgid, sig)
        return
    except PermissionError:
        pass
    # The scheduler runs part of the build as root, so its members can outlive every member this user may signal.
    if subprocess.run(["sudo", "-n", "true"], capture_output=True).returncode != 0:
        raise PermissionError(1, f"cannot signal process group {pgid}")
    result = subprocess.run(["sudo", "-n", "kill", f"-{int(sig)}", "--", f"-{pgid}"], capture_output=True, text=True)
    if result.returncode == 0:
        return
    if "No such process" in result.stderr:
        raise ProcessLookupError(3, f"process group {pgid} is gone")
    raise PermissionError(1, f"cannot signal process group {pgid}: {result.stderr.strip()}")


def stop(pgid, reap, signal_group=signal_group):
    """Terminates one process group, escalating to SIGKILL after ten seconds.

    reap collects the exited group leader, which otherwise stays signalable as a zombie until the grace runs out.

    A group whose remaining members cannot be signaled is left to the runner's own job cleanup."""
    for sig, grace in ((signal.SIGTERM, 10), (signal.SIGKILL, 10)):
        try:
            signal_group(pgid, sig)
        except ProcessLookupError:
            return
        except PermissionError as failure:
            print(f"watch-quiet-build: {failure}", file=sys.stderr, flush=True)
            return
        deadline = time.monotonic() + grace
        while time.monotonic() < deadline:
            try:
                signal_group(pgid, 0)
            except ProcessLookupError:
                return
            except PermissionError as failure:
                print(f"watch-quiet-build: {failure}", file=sys.stderr, flush=True)
                return
            reap()
            time.sleep(0.1)


def stop_tree(pid, run=lambda command: subprocess.run(command, capture_output=True, timeout=120)):
    """Ends one Windows process and every descendant, since Windows has no process groups to signal.

    run executes the taskkill command."""
    run(["taskkill", "/T", "/F", "/PID", str(pid)])


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--dump", action="store_true", help=argparse.SUPPRESS)
    parser.add_argument("--jcmd", default=None)
    parser.add_argument("--watch", type=Path)
    parser.add_argument("--report", type=Path)
    parser.add_argument("--first-report", type=float, default=900)
    parser.add_argument("--interval", type=float, default=900)
    parser.add_argument("--stop-after", type=float, default=3600)
    parser.add_argument("command", nargs="*")
    args = parser.parse_args()
    if args.dump:
        dump(args.jcmd, sys.stdout)
        return 0
    if args.watch is None or args.report is None or not args.command:
        parser.error("--watch, --report and a command are required")
    if args.jcmd is None and os.environ.get("JAVA_HOME"):
        candidate = Path(os.environ["JAVA_HOME"]) / "bin" / ("jcmd.exe" if os.name == "nt" else "jcmd")
        args.jcmd = str(candidate) if candidate.is_file() else None

    process = subprocess.Popen(args.command, start_new_session=True)
    output = BuildOutput(args.watch, args.report)
    last_activity = max(time.time(), output.latest())
    next_report = last_activity + args.first_report
    while process.poll() is None:
        time.sleep(min(1.0, args.interval / 4))
        changed = output.latest()
        now = time.time()
        if changed > last_activity:
            last_activity = changed
            next_report = changed + args.first_report
            continue
        quiet = now - last_activity
        if quiet >= args.stop_after:
            write_report(args.report, quiet, args.jcmd)
            print(f"watch-quiet-build: stopping quiet build after {quiet:.0f} seconds without output; "
                  f"process report: {args.report}", file=sys.stderr, flush=True)
            if os.name == "nt":
                stop_tree(process.pid)
                process.wait()
            else:
                stop(process.pid, process.poll)
            return 124
        if now >= next_report:
            write_report(args.report, quiet, args.jcmd)
            print(f"watch-quiet-build: no output for {quiet:.0f} seconds; process report: {args.report}",
                  file=sys.stderr, flush=True)
            next_report = now + args.interval
    return process.returncode


if __name__ == "__main__":
    sys.exit(main())
