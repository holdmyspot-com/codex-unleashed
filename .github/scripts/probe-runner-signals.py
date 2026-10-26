import json
import os
from pathlib import Path
import signal
import subprocess
import time


def probe_signal_sender():
    program = r'''
tracepoint:signal:signal_generate {
    printf("sender_pid=%d receiver_pid=%d signal=%d sender=%s receiver=%s code=%d\n",
        pid, args->pid, args->sig, comm, args->comm, args->code);
}
'''
    log = Path("signal-observer.log")
    observer = None
    marker = None
    try:
        with log.open("w") as output:
            observer = subprocess.Popen(
                ["sudo", "-n", "bpftrace", "-B", "line", "-e", program],
                stdout=output,
                stderr=subprocess.STDOUT,
                start_new_session=True,
            )
            marker = subprocess.Popen(["sleep", "30"], start_new_session=True)
            identity = f"sender_pid={os.getpid()} receiver_pid={marker.pid}"
            continued = f"{identity} signal={int(signal.SIGCONT)} "
            terminated = f"{identity} signal={int(signal.SIGTERM)} "
            deadline_ns = time.monotonic_ns() + 20_000_000_000
            sent_termination = False

            while time.monotonic_ns() < deadline_ns:
                captured = log.read_text(errors="replace")
                if terminated in captured:
                    marker.wait(timeout=5)
                    if marker.returncode != -int(signal.SIGTERM):
                        raise RuntimeError(
                            f"Marker exited {marker.returncode}, expected SIGTERM termination"
                        )
                    return {
                        "sender_pid": os.getpid(),
                        "receiver_pid": marker.pid,
                        "signal": int(signal.SIGTERM),
                        "status": "passed",
                    }
                if observer.poll() is not None:
                    raise RuntimeError(
                        f"Signal observer exited {observer.returncode} before capturing "
                        f"the marker's SIGTERM; inspect {log}"
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
                f"Signal observer did not capture the known sender within 20 seconds; inspect {log}"
            )
    finally:
        if marker is not None:
            if marker.poll() is None:
                marker.kill()
            marker.wait()
        if observer is not None:
            if observer.poll() is None:
                subprocess.run(
                    ["sudo", "-n", "kill", "-INT", "--", f"-{observer.pid}"],
                    check=True,
                )
                try:
                    observer.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    subprocess.run(
                        ["sudo", "-n", "kill", "-KILL", "--", f"-{observer.pid}"],
                        check=True,
                    )
                    observer.wait()


def main():
    try:
        receipt = probe_signal_sender()
    except (OSError, RuntimeError, subprocess.SubprocessError) as error:
        receipt = {"status": "failed", "error": str(error)}
    Path("signal-probe.json").write_text(json.dumps(receipt, indent=2) + "\n")
    print(json.dumps(receipt), flush=True)
    print(Path("signal-observer.log").read_text(errors="replace"), flush=True)
    return 0 if receipt["status"] == "passed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
