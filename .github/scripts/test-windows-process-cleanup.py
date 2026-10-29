"""Inject a launch-scope cleanup error after native process creation.

The injected error models an exceptional exit while releasing launch resources.
Native handle accounting must show no leaked process/thread handles afterward.
"""
import ctypes
import gc
from ctypes import wintypes
import json
import os
from pathlib import Path
import sys
sys.path.insert(0, sys.argv[1])
from build_scheduler.windows_process import ProcessTree
from build_scheduler import windows_api as api


class CleanupFailure(Exception):
    """Deliberate failure after the first launch resource has been released."""


class FailingCleanupTree(ProcessTree):
    """Inject one cleanup failure while still closing each requested handle."""
    def _close_handle(self, handle):
        """Close the native handle, then inject CleanupFailure once per tree after successful release."""
        super()._close_handle(handle)
        if getattr(self, "_fault_delivered", False) is False:
            self._fault_delivered = True
            raise CleanupFailure("injected launch cleanup failure")


def count_handles(kernel):
    """Return the calling process's current native handle count."""
    count = wintypes.DWORD()
    api.require_api_success(kernel.GetProcessHandleCount(kernel.GetCurrentProcess(), ctypes.byref(count)))
    return count.value


kernel = api.bind_kernel_api()
kernel.GetProcessHandleCount.argtypes = [wintypes.HANDLE, ctypes.POINTER(wintypes.DWORD)]
kernel.GetProcessHandleCount.restype = wintypes.BOOL
directory = Path(sys.argv[2]).resolve()
with (directory / "cleanup-output.log").open("wb") as output:
    with ProcessTree([sys.executable, "-c", "pass"], directory, os.environ, output) as tree:
        assert tree.wait(10_000) == 0
    before = count_handles(kernel)
    for attempt in range(3):
        try:
            FailingCleanupTree([sys.executable, "-c", "import time; time.sleep(60)"], directory, os.environ, output)
            raise AssertionError("injected cleanup failure was lost")
        except CleanupFailure:
            pass
    gc.collect()
    after = count_handles(kernel)
    print(json.dumps({"case": "construction_cleanup", "before": before, "after": after}), flush=True)
    assert after == before, "native handles leaked after failed construction"
    assert output.closed is False
