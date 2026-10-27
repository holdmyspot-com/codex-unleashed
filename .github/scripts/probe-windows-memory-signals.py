"""Observe native Windows memory signals through bounded owned load and release.

Run with native 64-bit Windows Python; uses only its standard library. Samples
low/high physical-memory conditions, available RAM, process working set, native
paging-output rate and pagefile occupancy before, during and after one touched
256 MiB allocation. Requires available RAM above that request plus 2 GiB and a
false low-memory condition before allocating. Each PDH interval is at least one
second; pagefile queries time out after ten seconds. Invalid native results
fail with their operation and status. The allocation, notification handles and
PDH query are released on every exit. Valid zero paging proves acquisition,
not paging recovery; this experiment changes no pagefile configuration.
"""
import contextlib
import ctypes
import json
import math
import platform
import subprocess
import sysconfig
import time


class PerformanceInfo(ctypes.Structure):
    """Native PERFORMANCE_INFORMATION page counts and system process counts."""
    _fields_ = [('size', ctypes.c_uint32),
        *[(name, ctypes.c_size_t) for name in ('commit_total', 'commit_limit',
          'commit_peak', 'physical_total', 'physical_available', 'system_cache',
          'kernel_total', 'kernel_paged', 'kernel_nonpaged', 'page_size')],
        ('handles', ctypes.c_uint32), ('processes', ctypes.c_uint32), ('threads', ctypes.c_uint32)]


class ProcessMemory(ctypes.Structure):
    """Native PROCESS_MEMORY_COUNTERS, whose working-set size is in bytes."""
    _fields_ = [('size', ctypes.c_uint32), ('faults', ctypes.c_uint32),
        *[(name, ctypes.c_size_t) for name in ('peak_working_set', 'working_set',
          'peak_paged_pool', 'paged_pool', 'peak_nonpaged_pool', 'nonpaged_pool',
          'commit_charge', 'peak_commit_charge')]]


class CounterValue(ctypes.Structure):
    """PDH_FMT_COUNTERVALUE with its selected 64-bit DOUBLE union member."""
    _fields_ = [('status', ctypes.c_uint32), ('rate', ctypes.c_double)]


def emit(event, **details):
    """Flush one diagnostic event and its measured values as JSON."""
    print(json.dumps({'event': event, **details}), flush=True)


def require_native_success(result, operation):
    """Raise a Windows error naming operation when its BOOL or pointer result is null."""
    if result in (None, 0):
        error = ctypes.get_last_error()
        raise OSError(error, f'{operation} returned a null result; Windows error {error}')
    return result


def require_counter_success(status, operation):
    """Reject a nonzero PDH operation status without inventing a counter value."""
    if status != 0:
        raise OSError(status, f'{operation} failed with PDH status 0x{status:08x}')


def bind_apis():
    """Bind pointer-width-safe documented APIs; return kernel, PSAPI and PDH DLLs.

    Requires native Windows. DLL or export acquisition errors remain visible.
    Acquires no resource handles and changes no machine state.
    """
    kernel = ctypes.WinDLL('kernel32', use_last_error=True)
    psapi = ctypes.WinDLL('psapi', use_last_error=True)
    pdh = ctypes.WinDLL('pdh')
    signatures = [
        (kernel, 'CreateMemoryResourceNotification', [ctypes.c_int], ctypes.c_void_p),
        (kernel, 'QueryMemoryResourceNotification', [ctypes.c_void_p, ctypes.POINTER(ctypes.c_int)], ctypes.c_int),
        (kernel, 'CloseHandle', [ctypes.c_void_p], ctypes.c_int),
        (kernel, 'GetCurrentProcess', [], ctypes.c_void_p),
        (kernel, 'VirtualAlloc', [ctypes.c_void_p, ctypes.c_size_t, ctypes.c_uint32, ctypes.c_uint32], ctypes.c_void_p),
        (kernel, 'VirtualFree', [ctypes.c_void_p, ctypes.c_size_t, ctypes.c_uint32], ctypes.c_int),
        (psapi, 'GetPerformanceInfo', [ctypes.POINTER(PerformanceInfo), ctypes.c_uint32], ctypes.c_int),
        (psapi, 'GetProcessMemoryInfo', [ctypes.c_void_p, ctypes.POINTER(ProcessMemory), ctypes.c_uint32], ctypes.c_int),
        (pdh, 'PdhOpenQueryW', [ctypes.c_wchar_p, ctypes.c_size_t, ctypes.POINTER(ctypes.c_void_p)], ctypes.c_uint32),
        (pdh, 'PdhAddEnglishCounterW', [ctypes.c_void_p, ctypes.c_wchar_p, ctypes.c_size_t, ctypes.POINTER(ctypes.c_void_p)], ctypes.c_uint32),
        (pdh, 'PdhCollectQueryData', [ctypes.c_void_p], ctypes.c_uint32),
        (pdh, 'PdhGetFormattedCounterValue', [ctypes.c_void_p, ctypes.c_uint32,
            ctypes.POINTER(ctypes.c_uint32), ctypes.POINTER(CounterValue)], ctypes.c_uint32),
        (pdh, 'PdhCloseQuery', [ctypes.c_void_p], ctypes.c_uint32)
    ]
    for library, name, arguments, result in signatures:
        operation = getattr(library, name)
        operation.argtypes = arguments
        operation.restype = result
    return kernel, psapi, pdh


def read_pagefiles():
    """Return current native pagefile records in their documented megabyte units.

    Uses the installed PowerShell CIM command with a ten-second deadline.
    An empty inventory remains an empty list. A failed command, timeout or
    malformed JSON fails the observation instead of reporting zero occupancy.
    """
    command = ('$ErrorActionPreference = "Stop"; '
        '$files = @(Get-CimInstance Win32_PageFileUsage | '
        'Select-Object Name, AllocatedBaseSize, CurrentUsage, PeakUsage); '
        'ConvertTo-Json -InputObject $files -Compress')
    result = subprocess.run(['pwsh', '-NoProfile', '-NonInteractive', '-Command', command],
        check=True, capture_output=True, text=True, timeout=10)
    records = json.loads(result.stdout)
    assert isinstance(records, list), records
    return records


def read_sample(kernel, psapi, pdh, query, counter, notifications):
    """Collect one validated native sample after at least a one-second interval.

    Supplied query/counter and low/high handles must remain open. Returns
    integer physical bytes and approximate pages/second, plus condition states
    distinct from query success, alongside separately queried pagefile records. Invalid
    observations raise with the precise native status; no value is substituted.
    """
    time.sleep(1)
    require_counter_success(pdh.PdhCollectQueryData(query), 'PdhCollectQueryData')
    value = CounterValue()
    counter_type = ctypes.c_uint32()
    status = pdh.PdhGetFormattedCounterValue(counter, 0x200, ctypes.byref(counter_type), ctypes.byref(value))
    require_counter_success(status, 'PdhGetFormattedCounterValue')
    assert value.status in (0, 1), {'invalid_counter_status': value.status}
    is_valid_rate = math.isfinite(value.rate) and value.rate >= 0
    assert is_valid_rate, {'invalid_pages_output_per_second': value.rate}

    physical = PerformanceInfo()
    physical.size = ctypes.sizeof(physical)
    require_native_success(psapi.GetPerformanceInfo(ctypes.byref(physical), physical.size), 'GetPerformanceInfo')
    process = ProcessMemory()
    process.size = ctypes.sizeof(process)
    require_native_success(psapi.GetProcessMemoryInfo(kernel.GetCurrentProcess(), ctypes.byref(process), process.size), 'GetProcessMemoryInfo')
    conditions = {}
    for name, handle in notifications.items():
        condition = ctypes.c_int()
        success = kernel.QueryMemoryResourceNotification(handle, ctypes.byref(condition))
        require_native_success(success, 'QueryMemoryResourceNotification:' + name)
        conditions[name] = {'query_success': success, 'condition': condition.value}
    assert physical.page_size > 0, physical.page_size
    return {'monotonic_ns': time.monotonic_ns(), 'physical_total_bytes': physical.physical_total * physical.page_size,
        'available_bytes': physical.physical_available * physical.page_size,
        'working_set_bytes': process.working_set, 'conditions': conditions,
        'pages_output_per_second': value.rate, 'pdh_operation_status': status,
        'pdh_counter_status': value.status, 'pagefiles_native_mb': read_pagefiles()}


def run_probe():
    """Require native signal acquisition and process load/release observations.

    The fixed 256 MiB request is refused before allocation unless the last
    available-memory sample exceeds it plus 2 GiB and low-memory is false.
    A working-set change below three quarters of the touched allocation fails
    attribution rather than claiming a passed response. Closes every owned
    handle and query and frees the mapping even if observations fail.
    """
    assert platform.system() == 'Windows', 'Requires native Windows'
    assert ctypes.sizeof(ctypes.c_void_p) == 8, 'Requires native 64-bit Python'
    emit('native_identity', machine=platform.machine(), python=platform.python_version(),
         native_platform=sysconfig.get_platform())
    kernel, psapi, pdh = bind_apis()
    with contextlib.ExitStack() as handles:
        notifications = {}
        for kind, name in ((0, 'low'), (1, 'high')):
            handle = require_native_success(kernel.CreateMemoryResourceNotification(kind), 'CreateMemoryResourceNotification:' + name)
            handles.callback(lambda owned=handle: require_native_success(kernel.CloseHandle(owned), 'CloseHandle'))
            notifications[name] = handle
        query = ctypes.c_void_p()
        require_counter_success(pdh.PdhOpenQueryW(None, 0, ctypes.byref(query)), 'PdhOpenQueryW')
        handles.callback(lambda: require_counter_success(pdh.PdhCloseQuery(query), 'PdhCloseQuery'))
        counter = ctypes.c_void_p()
        path = r'\Memory\Pages Output/sec'
        require_counter_success(pdh.PdhAddEnglishCounterW(query, path, 0, ctypes.byref(counter)), 'PdhAddEnglishCounterW')
        require_counter_success(pdh.PdhCollectQueryData(query), 'PdhCollectQueryData:initial')
        baseline = read_sample(kernel, psapi, pdh, query, counter, notifications)
        emit('baseline', **baseline)
        allocation_bytes = 256 * 1024 * 1024
        required_available_bytes = allocation_bytes + (2 * 1024 * 1024 * 1024)
        has_safe_probe_budget = baseline['available_bytes'] > required_available_bytes and baseline['conditions']['low']['condition'] == 0
        assert has_safe_probe_budget, {'insufficient_safe_probe_budget': baseline, 'required_available_bytes': required_available_bytes}
        pointer = require_native_success(kernel.VirtualAlloc(None, allocation_bytes, 0x3000, 4), 'VirtualAlloc')
        try:
            ctypes.memset(pointer, 0xA5, allocation_bytes)
            loaded = read_sample(kernel, psapi, pdh, query, counter, notifications)
            emit('loaded', allocated_bytes=allocation_bytes, **loaded)
        finally:
            require_native_success(kernel.VirtualFree(pointer, 0, 0x8000), 'VirtualFree')
        released = read_sample(kernel, psapi, pdh, query, counter, notifications)
        emit('released', **released)
        minimum_observed_change = (allocation_bytes * 3) // 4
        assert loaded['working_set_bytes'] > (baseline['working_set_bytes'] + minimum_observed_change), {'working_set_load_not_attributed': [baseline, loaded]}
        assert released['working_set_bytes'] < (loaded['working_set_bytes'] - minimum_observed_change), {'working_set_release_not_attributed': [loaded, released]}
        emit('native_memory_load_response_passed', allocated_bytes=allocation_bytes,
             paging_recovery='unaccepted: bounded load does not establish paging pressure or recovery')
    emit('native_resources_closed')


if __name__ == '__main__':
    run_probe()
