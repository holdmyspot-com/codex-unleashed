<#
.SYNOPSIS
Run compiled Windows runtime tests under a temporary local standard-user account.
.DESCRIPTION
Run on Windows with permission to create local users and grant filesystem access.
FOCUSED_RUNTIME/evidence must contain nextest binaries.json and cargo.json; cargo-nextest.exe
must be on PATH, and CODEX_REPO_ROOT and CARGO_TARGET_DIR identify the source and build.
Creates FOCUSED_RUNTIME/standard-user-runtime, copies metadata and nextest there, and
passes selected environment settings to the child through runtime-env.json.
Launches the child through Task Scheduler, outside the runner's job that forbids breakaway,
after granting the account the batch logon right; cleanup removes the task and that right.
Writes child output, launch details, test-exit, and cleanup evidence under evidence.
Returns the child exit code, or 78 if the non-elevated assertions were bypassed or a
successful result lacks the expected one-passed, zero-skipped report.
After account creation, finally attempts to stop its processes and remove the account
and profile. Setup, launch, evidence, or cleanup failures stop the script.
.PARAMETER TestFilter
Nextest filter expression selecting the already compiled test to run.
#>
param([Parameter(Mandatory = $true)][string]$TestFilter)

$ErrorActionPreference = 'Stop'

$jobProbeSource = @'
function Get-JobLimits {
    # Report whether this process is in a Windows job and whether that job permits breakaway.
    Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
public static class CodexJobProbe {
    [DllImport("kernel32.dll", SetLastError = true)]
    public static extern bool IsProcessInJob(IntPtr process, IntPtr job, out bool result);
    [DllImport("kernel32.dll")]
    public static extern IntPtr GetCurrentProcess();
    [DllImport("kernel32.dll", SetLastError = true)]
    public static extern bool QueryInformationJobObject(IntPtr job, int infoClass, byte[] info, int length, out int returned);
}
"@
    $inJob = $false
    [void][CodexJobProbe]::IsProcessInJob([CodexJobProbe]::GetCurrentProcess(), [IntPtr]::Zero, [ref]$inJob)
    $flags = $null
    if ($inJob) {
        # LimitFlags follows two 8-byte time limits in JOBOBJECT_BASIC_LIMIT_INFORMATION (class 2).
        $buffer = New-Object byte[] 64
        $returned = 0
        if ([CodexJobProbe]::QueryInformationJobObject([IntPtr]::Zero, 2, $buffer, $buffer.Length, [ref]$returned)) {
            $flags = [BitConverter]::ToUInt32($buffer, 16)
        }
    }
    @{
        inJob = $inJob
        limitFlags = $flags
        breakawayOk = ($null -ne $flags) -and (($flags -band 0x800) -ne 0)
        silentBreakawayOk = ($null -ne $flags) -and (($flags -band 0x1000) -ne 0)
    }
}
'@
. ([scriptblock]::Create($jobProbeSource))

# The GitHub runner runs steps inside a Windows job that forbids breakaway, which the
# daemon under test requires. Task Scheduler starts the child outside that job; a task
# that runs with a stored password needs the account to hold the batch logon right.
Add-Type -TypeDefinition @"
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;
public static class CodexBatchLogon {
    [StructLayout(LayoutKind.Sequential)]
    struct LsaUnicodeString { public ushort Length; public ushort MaximumLength; public IntPtr Buffer; }
    [StructLayout(LayoutKind.Sequential)]
    struct LsaObjectAttributes { public int Length; public IntPtr RootDirectory; public IntPtr ObjectName; public int Attributes; public IntPtr SecurityDescriptor; public IntPtr SecurityQualityOfService; }
    [DllImport("advapi32.dll")]
    static extern uint LsaOpenPolicy(IntPtr system, ref LsaObjectAttributes attributes, int access, out IntPtr policy);
    [DllImport("advapi32.dll")]
    static extern uint LsaAddAccountRights(IntPtr policy, byte[] sid, LsaUnicodeString[] rights, int count);
    [DllImport("advapi32.dll")]
    static extern uint LsaRemoveAccountRights(IntPtr policy, byte[] sid, bool allRights, LsaUnicodeString[] rights, int count);
    [DllImport("advapi32.dll")]
    static extern uint LsaClose(IntPtr policy);
    [DllImport("advapi32.dll")]
    static extern int LsaNtStatusToWinError(uint status);
    public static void Set(byte[] sid, bool grant) {
        LsaObjectAttributes attributes = new LsaObjectAttributes();
        IntPtr policy;
        // POLICY_CREATE_ACCOUNT | POLICY_LOOKUP_NAMES
        Check(LsaOpenPolicy(IntPtr.Zero, ref attributes, 0x810, out policy));
        IntPtr name = Marshal.StringToHGlobalUni("SeBatchLogonRight");
        try {
            LsaUnicodeString[] rights = { new LsaUnicodeString { Length = 34, MaximumLength = 36, Buffer = name } };
            Check(grant ? LsaAddAccountRights(policy, sid, rights, 1) : LsaRemoveAccountRights(policy, sid, true, null, 0));
        } finally {
            Marshal.FreeHGlobal(name);
            LsaClose(policy);
        }
    }
    static void Check(uint status) {
        if (status != 0) throw new Win32Exception(LsaNtStatusToWinError(status));
    }
}
"@
$evidence = Join-Path $env:FOCUSED_RUNTIME 'evidence'
Get-JobLimits | ConvertTo-Json | Set-Content (Join-Path $evidence 'parent-job.json')
$runtime = Join-Path $env:FOCUSED_RUNTIME 'standard-user-runtime'
$source = Join-Path $env:CODEX_REPO_ROOT 'codex-rs'
New-Item -ItemType Directory -Path $runtime -ErrorAction Stop | Out-Null
New-Item -ItemType Directory -Path (Join-Path $runtime 'tmp') | Out-Null
Copy-Item (Get-Command cargo-nextest.exe).Source (Join-Path $runtime 'cargo-nextest.exe')
Copy-Item (Join-Path $evidence 'binaries.json') $runtime
Copy-Item (Join-Path $evidence 'cargo.json') $runtime

$settings = @{
    TEMP = Join-Path $runtime 'tmp'
    TMP = Join-Path $runtime 'tmp'
    TMPDIR = Join-Path $runtime 'tmp'
    CODEX_REPO_ROOT = $env:CODEX_REPO_ROOT
    CARGO_TARGET_DIR = $env:CARGO_TARGET_DIR
    RUST_BACKTRACE = $env:RUST_BACKTRACE
    RUST_LIB_BACKTRACE = $env:RUST_LIB_BACKTRACE
    RUST_MIN_STACK = $env:RUST_MIN_STACK
    GIT_REDIRECT_STDIN = 'NUL'
    CARGO_TERM_COLOR = 'never'
    CODEX_RUNTIME_TEST_FILTER = $TestFilter
}
$settings | ConvertTo-Json | Set-Content (Join-Path $runtime 'runtime-env.json')
$childScript = Join-Path $runtime 'run-nextest.ps1'
(@'
$ErrorActionPreference = 'Stop'
'@ + "`n" + $jobProbeSource + @'

$settings = Get-Content (Join-Path $PSScriptRoot 'runtime-env.json') -Raw | ConvertFrom-Json
foreach ($property in $settings.PSObject.Properties) {
    [Environment]::SetEnvironmentVariable($property.Name, $property.Value, 'Process')
}
Get-JobLimits | ConvertTo-Json | Set-Content (Join-Path $PSScriptRoot 'child-job.json')
Set-Location (Join-Path $env:CODEX_REPO_ROOT 'codex-rs')
# Task Scheduler gives the child no console streams, so it records its own output and exit code.
& (Join-Path $PSScriptRoot 'cargo-nextest.exe') nextest run `
    --binaries-metadata (Join-Path $PSScriptRoot 'binaries.json') `
    --cargo-metadata (Join-Path $PSScriptRoot 'cargo.json') `
    --no-fail-fast --test-threads 8 --success-output immediate `
    -E $env:CODEX_RUNTIME_TEST_FILTER *> (Join-Path $PSScriptRoot 'runtime-output.log')
$code = $LASTEXITCODE
Set-Content (Join-Path $PSScriptRoot 'runtime-exit') $code
exit $code
'@) | Set-Content $childScript

$accountName = 'codexrt' + [guid]::NewGuid().ToString('N').Substring(0, 8)
$password = ConvertTo-SecureString ([guid]::NewGuid().ToString() + 'aA1!') -AsPlainText -Force
$user = New-LocalUser -Name $accountName -Password $password -AccountNeverExpires -PasswordNeverExpires
try {
    $writeGrant = '*' + $user.SID.Value + ':(OI)(CI)M'
    & icacls.exe $runtime /grant $writeGrant | Out-File (Join-Path $evidence 'runtime-permissions.log')
    if ($LASTEXITCODE -ne 0) { throw 'The standard user could not be granted access to its runtime directory.' }

    $readGrant = '*' + $user.SID.Value + ':(OI)(CI)RX'
    foreach ($path in @($env:CODEX_REPO_ROOT, $env:CARGO_TARGET_DIR)) {
        & icacls.exe $path /grant $readGrant /T /Q | Out-File -Append (Join-Path $evidence 'runtime-permissions.log')
        if ($LASTEXITCODE -ne 0) { throw "The standard user could not be granted read/execute access to $path." }
    }

    $username = $env:COMPUTERNAME + '\' + $accountName
    $sidBytes = New-Object byte[] $user.SID.BinaryLength
    $user.SID.GetBinaryForm($sidBytes, 0)
    [CodexBatchLogon]::Set($sidBytes, $true)
    $taskName = 'codex-runtime-' + $accountName
    $action = New-ScheduledTaskAction -Execute (Get-Command powershell.exe).Source -WorkingDirectory $source `
        -Argument ('-NoProfile -ExecutionPolicy Bypass -File "' + $childScript + '"')
    $taskSettings = New-ScheduledTaskSettingsSet -ExecutionTimeLimit (New-TimeSpan -Hours 2) -AllowStartIfOnBatteries
    $passwordBuffer = [Runtime.InteropServices.Marshal]::SecureStringToGlobalAllocUnicode($password)
    try {
        $plainPassword = [Runtime.InteropServices.Marshal]::PtrToStringUni($passwordBuffer)
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeGlobalAllocUnicode($passwordBuffer)
    }
    Register-ScheduledTask -TaskName $taskName -Action $action -Settings $taskSettings `
        -User $username -Password $plainPassword -RunLevel Limited | Out-Null
    $plainPassword = $null
    Start-ScheduledTask -TaskName $taskName
    $exitFile = Join-Path $runtime 'runtime-exit'
    $deadline = (Get-Date).AddHours(2)
    while (-not (Test-Path $exitFile)) {
        $state = (Get-ScheduledTask -TaskName $taskName).State
        $info = Get-ScheduledTaskInfo -TaskName $taskName
        if (($state -ne 'Running') -and ($info.LastRunTime -gt [datetime]'2000-01-01') -and -not (Test-Path $exitFile)) {
            Start-Sleep -Seconds 2
            if (-not (Test-Path $exitFile)) { throw "The standard-user task ended without an exit code (task result $($info.LastTaskResult))." }
        }
        if ((Get-Date) -gt $deadline) { throw 'The standard-user task did not finish within two hours.' }
        Start-Sleep -Seconds 2
    }
    $childExit = [int](Get-Content $exitFile -Raw).Trim()
    @{ childExit = $childExit; sid = $user.SID.Value; launcher = 'TaskScheduler' } |
        ConvertTo-Json | Set-Content (Join-Path $evidence 'runtime-launch.json')
    Copy-Item (Join-Path $runtime 'runtime-output.log') (Join-Path $evidence 'runtime-stdout.log')
    $output = Get-Content (Join-Path $evidence 'runtime-stdout.log') -Raw
    $output | Set-Content (Join-Path $evidence 'test.log')
    $childJob = Join-Path $runtime 'child-job.json'
    if (Test-Path $childJob) { Copy-Item $childJob $evidence }
    Write-Output $output

    $testExit = $childExit
    if ($output.Contains('requires a non-elevated Windows runner')) {
        $testExit = 78
        [Console]::Error.WriteLine('The test returned before exercising its non-elevated Windows assertions.')
    }
    if (($testExit -eq 0) -and ($output -notmatch '\b1 passed\b.*\b0 skipped\b')) {
        $testExit = 78
        [Console]::Error.WriteLine('The runtime result does not report exactly one passing test with zero skipped tests.')
    }
    $testExit | Set-Content (Join-Path $evidence 'test-exit')
} catch {
    # Record the failure before cleanup runs, so a cleanup error cannot hide it.
    $_ | Out-String | Tee-Object -FilePath (Join-Path $evidence 'runtime-error.txt') | Write-Host
    throw
} finally {
    $username = $env:COMPUTERNAME + '\' + $accountName
    if ($taskName) { Unregister-ScheduledTask -TaskName $taskName -Confirm:$false -ErrorAction SilentlyContinue }
    if ($sidBytes) { [CodexBatchLogon]::Set($sidBytes, $false) }
    $remaining = Get-Process -IncludeUserName | Where-Object UserName -eq $username
    $remaining | Stop-Process -Force
    $remaining | ForEach-Object { $_.WaitForExit() }
    Remove-LocalUser -Name $accountName
    # The task's logon session can hold the profile briefly after its processes exit.
    $profileDeadline = (Get-Date).AddMinutes(2)
    while ($true) {
        try {
            Get-CimInstance Win32_UserProfile | Where-Object SID -eq $user.SID.Value | Remove-CimInstance
            break
        } catch {
            if ((Get-Date) -gt $profileDeadline) { throw }
            Start-Sleep -Seconds 5
        }
    }
    'STANDARD-USER-CLEANUP-DONE' | Set-Content (Join-Path $evidence 'runtime-cleanup.txt')
}
exit $testExit
