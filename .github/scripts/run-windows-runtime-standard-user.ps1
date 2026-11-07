<#
.SYNOPSIS
Run compiled Windows runtime tests under a temporary local standard-user account.
.DESCRIPTION
Run on Windows with permission to create local users and grant filesystem access.
FOCUSED_RUNTIME/evidence must contain nextest binaries.json and cargo.json, and
CODEX_REPO_ROOT and CARGO_TARGET_DIR identify the source and build.
Creates FOCUSED_RUNTIME/standard-user-runtime, copies the metadata there, and passes
selected environment settings to the child through runtime-env.json.
The child runs the libtest binary directly from the test's package directory, as nextest
would: nextest 0.9.103 assigns each test to a job that forbids breakaway, which makes the
daemon under test refuse to start.
A SYSTEM scheduled task, outside the runner's job that forbids breakaway, logs the account
on with the batch logon right, loads its profile, starts the child, stops the account's
remaining processes, and unloads the profile; cleanup removes the task and that right.
Writes child output, launch details, test-exit, and cleanup evidence under evidence.
Returns the child exit code, or 78 if the non-elevated assertions were bypassed or a
successful result lacks libtest's one-passed, zero-ignored report.
After account creation, finally attempts to stop its processes and remove the account
and profile. Setup, launch, evidence, or cleanup failures stop the script.
.PARAMETER TestBinary
Name of the already compiled test binary in binaries.json.
.PARAMETER TestName
Exact libtest name of the test to run.
#>
param(
    [Parameter(Mandatory = $true)][string]$TestBinary,
    [Parameter(Mandatory = $true)][string]$TestName
)

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
# daemon under test requires. Task Scheduler starts the launcher outside that job, and the
# launcher's batch logon of the account needs the account to hold the batch logon right.
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
# Tests create Unix sockets under TEMP, and AF_UNIX paths must stay shorter than SUN_LEN (108 bytes),
# so the standard user's TEMP sits directly under the drive root instead of inside the runtime directory.
$shortTemp = Join-Path ([IO.Path]::GetPathRoot($runtime)) ('crt' + [guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Path $shortTemp -ErrorAction Stop | Out-Null
$binaries = @((Get-Content (Join-Path $evidence 'binaries.json') -Raw | ConvertFrom-Json).'rust-binaries'.PSObject.Properties.Value |
    Where-Object 'binary-name' -eq $TestBinary)
if ($binaries.Count -ne 1) { throw "binaries.json lists $($binaries.Count) binaries named $TestBinary." }
$package = @((Get-Content (Join-Path $evidence 'cargo.json') -Raw | ConvertFrom-Json).packages |
    Where-Object id -eq $binaries[0].'package-id')
if ($package.Count -ne 1) { throw "cargo.json lists $($package.Count) packages for $TestBinary." }

$settings = @{
    TEMP = $shortTemp
    TMP = $shortTemp
    TMPDIR = $shortTemp
    CODEX_REPO_ROOT = $env:CODEX_REPO_ROOT
    CARGO_TARGET_DIR = $env:CARGO_TARGET_DIR
    RUST_BACKTRACE = $env:RUST_BACKTRACE
    RUST_LIB_BACKTRACE = $env:RUST_LIB_BACKTRACE
    RUST_MIN_STACK = $env:RUST_MIN_STACK
    GIT_REDIRECT_STDIN = 'NUL'
    CARGO_TERM_COLOR = 'never'
    CODEX_RUNTIME_TEST_BINARY = $binaries[0].'binary-path'
    CODEX_RUNTIME_TEST_DIRECTORY = Split-Path $package[0].manifest_path
    CODEX_RUNTIME_TEST_NAME = $TestName
}
$settings | ConvertTo-Json | Set-Content (Join-Path $runtime 'runtime-env.json')
$childScript = Join-Path $runtime 'run-nextest.ps1'
(@'
$ErrorActionPreference = 'Stop'
# The launcher starts the child without console streams, so record any failure where the parent can read it.
trap {
    $_ | Out-String | Set-Content (Join-Path $PSScriptRoot 'child-error.txt')
    exit 1
}
'@ + "`n" + $jobProbeSource + @'

$settings = Get-Content (Join-Path $PSScriptRoot 'runtime-env.json') -Raw | ConvertFrom-Json
foreach ($property in $settings.PSObject.Properties) {
    [Environment]::SetEnvironmentVariable($property.Name, $property.Value, 'Process')
}
Get-JobLimits | ConvertTo-Json | Set-Content (Join-Path $PSScriptRoot 'child-job.json')
Set-Location $env:CODEX_RUNTIME_TEST_DIRECTORY
# The launcher gives the child no console streams, so it records its own output and exit code.
# Windows PowerShell turns redirected native stderr into errors, which Stop would make terminating;
# the test binary's own exit code reports the outcome.
$ErrorActionPreference = 'Continue'
& $env:CODEX_RUNTIME_TEST_BINARY --exact $env:CODEX_RUNTIME_TEST_NAME --nocapture --test-threads 1 `
    *> (Join-Path $PSScriptRoot 'runtime-output.log')
$code = $LASTEXITCODE
Set-Content (Join-Path $PSScriptRoot 'runtime-exit') $code
exit $code
'@) | Set-Content $childScript

# A task that logs on with the account's stored password leaves the account's profile hive
# loaded after the task and all of its processes end (still loaded after 15 minutes in run
# 42384469325), so Remove-CimInstance cannot delete the profile and cleanup fails. A SYSTEM
# task instead runs this launcher, which owns the logon token and profile handle: it unloads
# the profile and closes the token itself once the account's processes are gone, after which
# the profile can be deleted at once. CreateProcessAsUser needs SeAssignPrimaryTokenPrivilege,
# which SYSTEM holds and the runner's administrator does not.
# SYSTEM runs this script and reads the account's password, so its directory admits only
# SYSTEM and Administrators; the standard user can write to the runtime directory.
$launcher = Join-Path $env:FOCUSED_RUNTIME 'standard-user-launcher'
New-Item -ItemType Directory -Path $launcher -ErrorAction Stop | Out-Null
& icacls.exe $launcher /inheritance:r /grant:r '*S-1-5-18:(OI)(CI)F' '*S-1-5-32-544:(OI)(CI)F' |
    Out-File -Append (Join-Path $evidence 'runtime-permissions.log')
if ($LASTEXITCODE -ne 0) { throw "The launcher directory $launcher could not be restricted." }
$launcherScript = Join-Path $launcher 'launch-standard-user.ps1'
@'
$ErrorActionPreference = 'Stop'
# Task Scheduler discards the launcher's error stream, so record any failure where the parent can read it.
trap {
    $_ | Out-String | Set-Content (Join-Path $PSScriptRoot 'launcher-error.txt')
    exit 1
}
Add-Type -TypeDefinition @"
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;
public sealed class CodexUserLaunch {
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    struct StartupInfo { public int Size; public string Reserved, Desktop, Title; public int X, Y, XSize, YSize, XCountChars, YCountChars, FillAttribute, Flags; public short ShowWindow, Reserved2Size; public IntPtr Reserved2, StdInput, StdOutput, StdError; }
    [StructLayout(LayoutKind.Sequential)]
    struct ProcessInformation { public IntPtr Process, Thread; public int ProcessId, ThreadId; }
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    struct ProfileInfo { public int Size, Flags; public string UserName, ProfilePath, DefaultPath, ServerName, PolicyPath; public IntPtr Profile; }
    [DllImport("advapi32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    static extern bool LogonUser(string user, string domain, string password, int logonType, int provider, out IntPtr token);
    [DllImport("userenv.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    static extern bool LoadUserProfile(IntPtr token, ref ProfileInfo profile);
    [DllImport("userenv.dll", SetLastError = true)]
    static extern bool UnloadUserProfile(IntPtr token, IntPtr profile);
    [DllImport("userenv.dll", SetLastError = true)]
    static extern bool CreateEnvironmentBlock(out IntPtr environment, IntPtr token, bool inherit);
    [DllImport("userenv.dll")]
    static extern bool DestroyEnvironmentBlock(IntPtr environment);
    [DllImport("advapi32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    static extern bool CreateProcessAsUser(IntPtr token, string application, string commandLine, IntPtr processAttributes, IntPtr threadAttributes, bool inheritHandles, int flags, IntPtr environment, string directory, ref StartupInfo startup, out ProcessInformation process);
    [DllImport("kernel32.dll", SetLastError = true)]
    static extern uint WaitForSingleObject(IntPtr handle, uint milliseconds);
    [DllImport("kernel32.dll", SetLastError = true)]
    static extern bool GetExitCodeProcess(IntPtr process, out int exitCode);
    [DllImport("kernel32.dll")]
    static extern bool CloseHandle(IntPtr handle);
    const int LOGON32_LOGON_BATCH = 4;
    const int PI_NOUI = 1;
    const int CREATE_UNICODE_ENVIRONMENT = 0x400;
    const int CREATE_NO_WINDOW = 0x08000000;
    IntPtr token;
    IntPtr profile;
    IntPtr process;

    // Logs the local account on, loads its profile, and starts commandLine as that account in directory.
    public static CodexUserLaunch Start(string user, string password, string commandLine, string directory) {
        CodexUserLaunch launch = new CodexUserLaunch();
        if (!LogonUser(user, ".", password, LOGON32_LOGON_BATCH, 0, out launch.token)) throw new Win32Exception();
        try {
            ProfileInfo profileInfo = new ProfileInfo { Flags = PI_NOUI, UserName = user };
            profileInfo.Size = Marshal.SizeOf(profileInfo);
            if (!LoadUserProfile(launch.token, ref profileInfo)) throw new Win32Exception();
            launch.profile = profileInfo.Profile;
            IntPtr environment;
            if (!CreateEnvironmentBlock(out environment, launch.token, false)) throw new Win32Exception();
            try {
                StartupInfo startup = new StartupInfo();
                startup.Size = Marshal.SizeOf(startup);
                ProcessInformation created;
                if (!CreateProcessAsUser(launch.token, null, commandLine, IntPtr.Zero, IntPtr.Zero, false,
                        CREATE_UNICODE_ENVIRONMENT | CREATE_NO_WINDOW, environment, directory, ref startup, out created)) {
                    throw new Win32Exception();
                }
                CloseHandle(created.Thread);
                launch.process = created.Process;
            } finally {
                DestroyEnvironmentBlock(environment);
            }
        } catch {
            launch.Unload();
            throw;
        }
        return launch;
    }

    // Waits for the started process to exit and returns its exit code.
    public int WaitForExit() {
        if (WaitForSingleObject(process, 0xFFFFFFFF) != 0) throw new Win32Exception();
        int exitCode;
        if (!GetExitCodeProcess(process, out exitCode)) throw new Win32Exception();
        return exitCode;
    }

    // Unloads the profile and closes the logon token; reports a profile that stays loaded.
    public void Unload() {
        try {
            if (profile != IntPtr.Zero && !UnloadUserProfile(token, profile)) throw new Win32Exception();
        } finally {
            if (process != IntPtr.Zero) CloseHandle(process);
            CloseHandle(token);
            process = IntPtr.Zero;
            profile = IntPtr.Zero;
            token = IntPtr.Zero;
        }
    }
}
"@
$config = Get-Content (Join-Path $PSScriptRoot 'launcher.json') -Raw | ConvertFrom-Json
$password = (Get-Content -LiteralPath $config.passwordFile -Raw).Trim()
Remove-Item -LiteralPath $config.passwordFile
$launch = [CodexUserLaunch]::Start($config.accountName, $password, $config.commandLine, $config.workingDirectory)
$password = $null
try {
    $childExit = $launch.WaitForExit()
    # A process the test leaves running, such as a daemon that broke away, keeps the profile
    # hive open, so UnloadUserProfile could not release it.
    $remaining = Get-Process -IncludeUserName | Where-Object UserName -eq $config.userName
    $remaining | Stop-Process -Force
    $remaining | ForEach-Object { $_.WaitForExit() }
} finally {
    $launch.Unload()
}
@{ childExit = $childExit; profileUnloaded = $true } | ConvertTo-Json | Set-Content (Join-Path $PSScriptRoot 'launcher-result.json')
'@ | Set-Content $launcherScript

$accountName = 'codexrt' + [guid]::NewGuid().ToString('N').Substring(0, 8)
$password = ConvertTo-SecureString ([guid]::NewGuid().ToString() + 'aA1!') -AsPlainText -Force
$user = New-LocalUser -Name $accountName -Password $password -AccountNeverExpires -PasswordNeverExpires
try {
    $writeGrant = '*' + $user.SID.Value + ':(OI)(CI)M'
    foreach ($path in @($runtime, $shortTemp)) {
        & icacls.exe $path /grant $writeGrant | Out-File -Append (Join-Path $evidence 'runtime-permissions.log')
        if ($LASTEXITCODE -ne 0) { throw "The standard user could not be granted access to $path." }
    }

    $readGrant = '*' + $user.SID.Value + ':(OI)(CI)RX'
    foreach ($path in @($env:CODEX_REPO_ROOT, $env:CARGO_TARGET_DIR)) {
        & icacls.exe $path /grant $readGrant /T /Q | Out-File -Append (Join-Path $evidence 'runtime-permissions.log')
        if ($LASTEXITCODE -ne 0) { throw "The standard user could not be granted read/execute access to $path." }
    }

    $username = $env:COMPUTERNAME + '\' + $accountName
    $sidBytes = New-Object byte[] $user.SID.BinaryLength
    $user.SID.GetBinaryForm($sidBytes, 0)
    [CodexBatchLogon]::Set($sidBytes, $true)
    $passwordFile = Join-Path $launcher 'password.txt'
    $passwordBuffer = [Runtime.InteropServices.Marshal]::SecureStringToGlobalAllocUnicode($password)
    try {
        [Runtime.InteropServices.Marshal]::PtrToStringUni($passwordBuffer) | Set-Content -LiteralPath $passwordFile
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeGlobalAllocUnicode($passwordBuffer)
    }
    $powershell = (Get-Command powershell.exe).Source
    @{
        accountName = $accountName
        userName = $username
        passwordFile = $passwordFile
        commandLine = '"' + $powershell + '" -NoProfile -ExecutionPolicy Bypass -File "' + $childScript + '"'
        workingDirectory = $source
    } | ConvertTo-Json | Set-Content (Join-Path $launcher 'launcher.json')

    $taskName = 'codex-runtime-' + $accountName
    $action = New-ScheduledTaskAction -Execute $powershell -WorkingDirectory $launcher `
        -Argument ('-NoProfile -ExecutionPolicy Bypass -File "' + $launcherScript + '"')
    $principal = New-ScheduledTaskPrincipal -UserId 'S-1-5-18' -LogonType ServiceAccount -RunLevel Highest
    $taskSettings = New-ScheduledTaskSettingsSet -ExecutionTimeLimit (New-TimeSpan -Hours 2) -AllowStartIfOnBatteries
    Register-ScheduledTask -TaskName $taskName -Action $action -Principal $principal -Settings $taskSettings | Out-Null
    Start-ScheduledTask -TaskName $taskName
    $deadline = (Get-Date).AddHours(2)
    while ($true) {
        $state = (Get-ScheduledTask -TaskName $taskName).State
        $info = Get-ScheduledTaskInfo -TaskName $taskName
        if (($state -ne 'Running') -and ($info.LastRunTime -gt [datetime]'2000-01-01')) { break }
        if ((Get-Date) -gt $deadline) { throw 'The standard-user launcher did not finish within two hours.' }
        Start-Sleep -Seconds 2
    }
    $launcherError = Join-Path $launcher 'launcher-error.txt'
    if (Test-Path $launcherError) { throw "The standard-user launcher failed: $(Get-Content $launcherError -Raw)" }
    $launcherResult = Join-Path $launcher 'launcher-result.json'
    if (-not (Test-Path $launcherResult)) {
        throw "The standard-user launcher ended without a result (task result $($info.LastTaskResult))."
    }
    Copy-Item $launcherResult (Join-Path $evidence 'runtime-launch.json')
    $exitFile = Join-Path $runtime 'runtime-exit'
    if (-not (Test-Path $exitFile)) { throw 'The standard-user child ended without an exit code.' }
    $childExit = [int](Get-Content $exitFile -Raw).Trim()
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
    if (($testExit -eq 0) -and ($output -notmatch 'test result: ok\. 1 passed; 0 failed; 0 ignored;')) {
        $testExit = 78
        [Console]::Error.WriteLine('The runtime result does not report exactly one passing test with zero ignored tests.')
    }
    $testExit | Set-Content (Join-Path $evidence 'test-exit')
} catch {
    foreach ($path in @((Join-Path $runtime 'child-error.txt'), (Join-Path $runtime 'child-job.json'),
            (Join-Path $launcher 'launcher-error.txt'))) {
        if (Test-Path $path) { Copy-Item $path $evidence }
    }
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
    Get-CimInstance Win32_UserProfile | Where-Object SID -eq $user.SID.Value | Remove-CimInstance
    Remove-Item -LiteralPath $launcher -Recurse -Force
    Remove-Item -LiteralPath $shortTemp -Recurse -Force
    'STANDARD-USER-CLEANUP-DONE' | Set-Content (Join-Path $evidence 'runtime-cleanup.txt')
}
exit $testExit
