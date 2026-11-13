# Adds a page file to a running Windows runner, without a reboot, and checks that the commit limit grew.
#
# Windows refuses an allocation once the system commit charge reaches the commit limit, which is physical memory plus
# the page files, even when most physical memory is free. rustc and clippy-driver commit several times the memory they
# touch, so a hosted runner with 16 GB of memory and a 3 GB page file runs out of commit while gigabytes stay free.
# Committed pages that are never touched are never written to the page file, so a large page file costs only disk space.
param(
  # Page file path on a local volume, such as D:\pagefile.sys.
  [Parameter(Mandatory = $true)][string]$Path,
  [Parameter(Mandatory = $true)][int]$MinimumMiB,
  [Parameter(Mandatory = $true)][int]$MaximumMiB
)

$ErrorActionPreference = 'Stop'

if ($MinimumMiB -le 0 -or $MaximumMiB -lt $MinimumMiB) {
  throw "Expected 0 < MinimumMiB <= MaximumMiB, not $MinimumMiB and $MaximumMiB"
}

Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;

public static class PageFile {
  [StructLayout(LayoutKind.Sequential)]
  private struct UnicodeString {
    public ushort Length;
    public ushort MaximumLength;
    [MarshalAs(UnmanagedType.LPWStr)] public string Buffer;
  }

  [DllImport("ntdll.dll")]
  private static extern int RtlAdjustPrivilege(int privilege, bool enable, bool currentThread, out bool wasEnabled);

  [DllImport("ntdll.dll")]
  private static extern int NtCreatePagingFile(ref UnicodeString name, ref long minimumSize, ref long maximumSize,
    uint priority);

  // SeCreatePagefilePrivilege, which administrators hold but have disabled by default.
  private const int CreatePagefilePrivilege = 15;

  public static void Create(string ntPath, long minimumBytes, long maximumBytes) {
    bool wasEnabled;
    int status = RtlAdjustPrivilege(CreatePagefilePrivilege, true, false, out wasEnabled);
    if (status != 0) {
      throw new InvalidOperationException(String.Format("RtlAdjustPrivilege failed with NTSTATUS 0x{0:X8}", status));
    }
    UnicodeString name = new UnicodeString {
      Length = (ushort)(ntPath.Length * 2),
      MaximumLength = (ushort)((ntPath.Length + 1) * 2),
      Buffer = ntPath
    };
    status = NtCreatePagingFile(ref name, ref minimumBytes, ref maximumBytes, 0);
    if (status != 0) {
      throw new InvalidOperationException(String.Format("NtCreatePagingFile failed with NTSTATUS 0x{0:X8}", status));
    }
  }
}
'@

function Get-CommitLimitMiB {
  [math]::Round((Get-CimInstance Win32_PerfFormattedData_PerfOS_Memory).CommitLimit / 1MB)
}

$fullPath = [System.IO.Path]::GetFullPath($Path)
$before = Get-CommitLimitMiB
[PageFile]::Create("\??\$fullPath", [long]$MinimumMiB * 1MB, [long]$MaximumMiB * 1MB)
$after = Get-CommitLimitMiB
$usage = @(Get-CimInstance Win32_PageFileUsage | Where-Object { $_.Name -eq $fullPath })
Write-Output ('page_file={0} minimum_mib={1} maximum_mib={2} commit_limit_mib_before={3} commit_limit_mib_after={4}' -f `
  $fullPath, $MinimumMiB, $MaximumMiB, $before, $after)
foreach ($file in @(Get-CimInstance Win32_PageFileUsage)) {
  Write-Output ('page_file name={0} allocated_mib={1}' -f $file.Name, $file.AllocatedBaseSize)
}
if ($usage.Count -ne 1) {
  throw "Windows does not list $fullPath as a page file in use"
}
# Allow for the commit limit that Windows reserves for itself while the new file is set up.
if ($after - $before -lt $MinimumMiB / 2) {
  throw "The commit limit grew by $($after - $before) MiB, not about $MinimumMiB MiB"
}
