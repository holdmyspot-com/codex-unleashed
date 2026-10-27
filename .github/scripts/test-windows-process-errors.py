"""Keep native creation/body failures primary when resource cleanup also fails."""
import os
from pathlib import Path
import sys
sys.path.insert(0, sys.argv[1])
from build_scheduler.windows_process import ProcessTree


class CleanupFailure(Exception):
    """Synthetic cleanup failure after resources have been released."""


class LaunchCleanupFailure(ProcessTree):
    """Inject one error at the real native handle release boundary."""
    def _close_handle(self, handle):
        super()._close_handle(handle)
        if getattr(self, "_fault_delivered", False) is False:
            self._fault_delivered = True
            raise CleanupFailure("injected launch cleanup failure")


class CloseFailure(ProcessTree):
    """Inject an error after the real whole-tree abort and wait operation."""
    def _terminate_and_wait(self):
        super()._terminate_and_wait()
        raise CleanupFailure("injected close cleanup failure")


directory = Path(sys.argv[2]).resolve()
with (directory / "primary-error.log").open("wb") as output:
    invalid = directory / "primary-invalid.exe"
    invalid.write_bytes(b"not a Windows executable")
    try:
        LaunchCleanupFailure([str(invalid)], directory, os.environ, output)
        raise AssertionError("invalid executable launched")
    except OSError as error:
        assert isinstance(error.__cause__, CleanupFailure), "cleanup failure was not attached"
        print("NATIVE_CREATION_ERROR_PRESERVED", error.winerror, flush=True)
    try:
        with CloseFailure([sys.executable, "-c", "import time; time.sleep(60)"], directory, os.environ, output):
            raise ValueError("body failure")
    except ValueError as error:
        assert str(error) == "body failure"
        assert isinstance(error.__cause__, CleanupFailure), "cleanup failure was not attached"
        print("BODY_ERROR_PRESERVED", flush=True)
    assert output.closed is False
