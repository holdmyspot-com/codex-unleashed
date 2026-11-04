"""Run a build command and explain it, then stop it, when its logs stay quiet.

The release build writes every stage's output under one evidence directory. When no file there changes for
--first-report seconds, this writes a report of every user process to --report: its state, kernel wait channel,
current system call, tracer and kernel stacks, plus a Java and a mixed Java and native thread dump of each Java process.
Zombies are listed with their name, since they have no command line. It repeats every --interval seconds
while the build stays quiet, and once the build has been quiet for --stop-after seconds it terminates the command's
process group and exits 124. Otherwise it exits with the command's own status.

Reading other users' kernel stacks and attaching to their JVMs needs root, so the report is produced by rerunning this
script with --dump under non-interactive sudo when that is available, and as the current user otherwise.
"""
import argparse
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
    if os.geteuid() == 0 and uid != 0:
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


def dump(jcmd, out):
    """Writes one line per user process, then its threads' kernel stacks and any JVM thread dump."""
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
        # The tracer names the process that must resume a tracee held in a ptrace stop.
        out.write(f"pid={pid} ppid={fields.get('PPid', '?')} tracer={fields.get('TracerPid', '?')} uid={uid} "
                  f"state={state} wchan={wchan} "
                  f"syscall={syscall} cmd={cmdline[:400]}\n")
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


def latest_change(directory, report):
    """Returns the newest modification time of any file under the watched directory except this watcher's report."""
    newest = 0.0
    excluded = os.path.abspath(report)
    for root, _, files in os.walk(directory):
        for name in files:
            if os.path.abspath(os.path.join(root, name)) == excluded:
                continue
            try:
                newest = max(newest, os.stat(os.path.join(root, name)).st_mtime)
            except OSError:
                pass
    return newest


def write_report(report, quiet, jcmd):
    """Appends one process report, as root when non-interactive sudo is available."""
    with open(report, "a") as out:
        out.write(f"=== quiet_seconds={quiet:.0f} time={time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime())}\n")
        out.flush()
        command = [sys.executable, "-I", os.path.abspath(__file__), "--dump"]
        if jcmd:
            command += ["--jcmd", jcmd]
        if os.geteuid() != 0 and subprocess.run(["sudo", "-n", "true"], capture_output=True).returncode == 0:
            command = ["sudo", "-n", *command]
        try:
            subprocess.run(command, stdout=out, stderr=out, timeout=600)
        except subprocess.TimeoutExpired:
            out.write("<process report timed out>\n")


def stop(process):
    """Terminates the command's process group, escalating to SIGKILL after ten seconds."""
    for sig, grace in ((signal.SIGTERM, 10), (signal.SIGKILL, 10)):
        try:
            os.killpg(process.pid, sig)
        except ProcessLookupError:
            return
        deadline = time.monotonic() + grace
        while time.monotonic() < deadline:
            try:
                os.killpg(process.pid, 0)
            except ProcessLookupError:
                return
            process.poll()
            time.sleep(0.1)


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
        candidate = Path(os.environ["JAVA_HOME"]) / "bin" / "jcmd"
        args.jcmd = str(candidate) if candidate.is_file() else None

    process = subprocess.Popen(args.command, start_new_session=True)
    last_activity = max(time.time(), latest_change(args.watch, args.report))
    next_report = last_activity + args.first_report
    while process.poll() is None:
        time.sleep(min(1.0, args.interval / 4))
        changed = latest_change(args.watch, args.report)
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
            stop(process)
            return 124
        if now >= next_report:
            write_report(args.report, quiet, args.jcmd)
            print(f"watch-quiet-build: no output for {quiet:.0f} seconds; process report: {args.report}",
                  file=sys.stderr, flush=True)
            next_report = now + args.interval
    return process.returncode


if __name__ == "__main__":
    sys.exit(main())
