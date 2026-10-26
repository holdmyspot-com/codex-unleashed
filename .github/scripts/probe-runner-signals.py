import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import time


def observe_command_signals(command):
    program = r'''
tracepoint:signal:signal_generate /args->sig != 17 && args->sig != 23/ {
    if (args->sig == 9) {
        printf("sender_pid=%d receiver_pid=%d signal=%d sender=%s receiver=%s code=%d kernel_stack=%s\n",
            pid, args->pid, args->sig, comm, args->comm, args->code, kstack());
    } else {
        printf("sender_pid=%d receiver_pid=%d signal=%d sender=%s receiver=%s code=%d\n",
            pid, args->pid, args->sig, comm, args->comm, args->code);
    }
}
kprobe:oom_kill_process {
    printf("oom_kill_process current_pid=%d current_comm=%s kernel_stack=%s\n", pid, comm, kstack());
}
tracepoint:oom:mark_victim {
    printf("oom_victim_pid=%d current_pid=%d current_comm=%s kernel_stack=%s\n",
        args->pid, pid, comm, kstack());
}
'''
    log = Path("signal-observer.log")
    observer = None
    observers = []
    marker = None
    output = None
    try:
        log.write_text("")
        output = subprocess.Popen(["tee", str(log)], stdin=subprocess.PIPE)
        observer = subprocess.Popen(
            ["sudo", "-n", "bpftrace", "-B", "line", "-e", program],
            stdout=output.stdin,
            stderr=subprocess.STDOUT,
            start_new_session=True
        )
        observers.append(observer)
        journal = subprocess.Popen(
            ["sudo", "-n", "journalctl", "--follow", "--lines=0",
             "--output=short-monotonic", "--priority=info"],
            stdout=output.stdin,
            stderr=subprocess.STDOUT,
            start_new_session=True
        )
        observers.append(journal)
        resources = subprocess.Popen(
            ["top", "--batch-mode", "--delay=5", "--scale-summary-mem=m",
             "--sort-override=%MEM", "--width=160"],
            stdout=output.stdin,
            stderr=subprocess.STDOUT,
            start_new_session=True
        )
        observers.append(resources)
        output.stdin.close()
        marker = subprocess.Popen(["sleep", "30"], start_new_session=True)
        identity = f"sender_pid={os.getpid()} receiver_pid={marker.pid}"
        continued = f"{identity} signal={int(signal.SIGCONT)} "
        terminated = f"{identity} signal={int(signal.SIGTERM)} "
        journal_marker = f"journal-ready-{os.getpid()}"
        deadline_ns = time.monotonic_ns() + 20_000_000_000
        sent_termination = False

        while time.monotonic_ns() < deadline_ns:
            captured = log.read_text(errors="replace")
            telemetry_ready = ("MiB Mem" in captured) and (journal_marker in captured)
            if (terminated in captured) and telemetry_ready:
                marker.wait(timeout=5)
                if marker.returncode != -int(signal.SIGTERM):
                    raise RuntimeError(
                        f"Marker exited {marker.returncode}, expected SIGTERM termination"
                    )
                receipt = {
                    "sender_pid": os.getpid(),
                    "receiver_pid": marker.pid,
                    "signal": int(signal.SIGTERM),
                    "status": "passed"
                }
                if command:
                    subprocess.run(["ps", "-eo", "pid,ppid,comm"], check=True)
                    receipt["command_exit"] = subprocess.run(command).returncode
                for diagnostic in observers:
                    if diagnostic.poll() is not None:
                        raise RuntimeError(
                            f"Diagnostic {diagnostic.args[0]} exited {diagnostic.returncode} "
                            f"during the command; inspect {log}"
                        )
                return receipt
            for diagnostic in observers:
                if diagnostic.poll() is not None:
                    raise RuntimeError(
                        f"Diagnostic {diagnostic.args[0]} exited {diagnostic.returncode} "
                        f"before readiness; inspect {log}"
                    )
            if journal_marker not in captured:
                subprocess.run(
                    ["logger", "--priority=user.warning", "--tag=codex-runner-signal-probe",
                     journal_marker], check=True
                )
            if sent_termination:
                time.sleep(0.1)
                continue
            if continued in captured:
                marker.send_signal(signal.SIGTERM)
                sent_termination = True
            else:
                marker.send_signal(signal.SIGCONT)
            time.sleep(0.1)

        raise RuntimeError(
            f"Signal or resource diagnostics did not become ready within 20 seconds; inspect {log}"
        )
    finally:
        if marker is not None:
            if marker.poll() is None:
                marker.kill()
            marker.wait()
        for diagnostic in reversed(observers):
            if diagnostic.poll() is None:
                subprocess.run(
                    ["sudo", "-n", "kill", "-INT", "--", f"-{diagnostic.pid}"],
                    check=True,
                )
                try:
                    diagnostic.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    subprocess.run(
                        ["sudo", "-n", "kill", "-KILL", "--", f"-{diagnostic.pid}"],
                        check=True,
                    )
                    diagnostic.wait()
        if output is not None:
            output.stdin.close()
            try:
                output.wait(timeout=5)
            except subprocess.TimeoutExpired:
                output.kill()
                output.wait()


def main():
    try:
        receipt = observe_command_signals(sys.argv[1:])
    except (OSError, RuntimeError, subprocess.SubprocessError) as error:
        receipt = {"status": "failed", "error": str(error)}
    Path("signal-probe.json").write_text(json.dumps(receipt, indent=2) + "\n")
    print(json.dumps(receipt), flush=True)
    if receipt["status"] == "failed":
        return 1
    command_exit = receipt.get("command_exit", 0)
    if command_exit < 0:
        return 128 - command_exit
    return command_exit


if __name__ == "__main__":
    raise SystemExit(main())
