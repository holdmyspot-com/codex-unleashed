# Collects host drive observations and three independent PowerShell startup results.
# OutputDirectory receives arm-launch-controls.json; the workflow owns its removal after upload.
# The KEEP-only and KEEP+SystemRoot children do not inherit other host environment variables.
param(
    [Parameter(Mandatory = $true)]
    [string]$OutputDirectory
)

$ErrorActionPreference = 'Stop'
if ($IsWindows -eq $false) {
    throw 'ARM launch controls require native Windows.'
}
if ([System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture -ne 'Arm64') {
    throw 'ARM launch controls require an ARM64 operating system.'
}

New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$program = (Get-Command pwsh.exe -CommandType Application -ErrorAction Stop).Source
$results = [System.Collections.Generic.List[object]]::new()
foreach ($control in @('host', 'keep-only', 'keep-systemroot')) {
    $start = [System.Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $program
    $start.UseShellExecute = $false
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    if ($control -ne 'host') {
        $start.Environment.Clear()
        $start.Environment['KEEP'] = 'preserved'
    }
    if ($control -eq 'keep-systemroot') {
        if ([string]::IsNullOrEmpty($env:SystemRoot)) {
            throw 'The SystemRoot control requires a nonempty host SystemRoot.'
        }
        $start.Environment['SystemRoot'] = $env:SystemRoot
    }
    foreach ($argument in @('-NoLogo', '-NoProfile', '-NonInteractive', '-Command', "[Console]::WriteLine('startup-ok'); Write-Output 'module-ok'")) {
        $start.ArgumentList.Add($argument)
    }

    $child = [System.Diagnostics.Process]::new()
    $started = $false
    try {
        $child.StartInfo = $start
        $started = $child.Start()
        if ($started -eq $false) {
            throw "PowerShell control $control did not start."
        }
        $stdout = $child.StandardOutput.ReadToEndAsync()
        $stderr = $child.StandardError.ReadToEndAsync()
        $completed = $child.WaitForExit(30000)
        if ($completed -eq $false) {
            $child.Kill($true)
            $child.WaitForExit()
        }
        $results.Add([ordered]@{
            control = $control
            completedWithin30Seconds = $completed
            exitCode = $child.ExitCode
            stdout = $stdout.GetAwaiter().GetResult()
            stderr = $stderr.GetAwaiter().GetResult()
        })
    }
    finally {
        if ($started -and $child.HasExited -eq $false) {
            $child.Kill($true)
            $child.WaitForExit()
        }
        $child.Dispose()
    }
}

$drives = foreach ($drive in [System.IO.DriveInfo]::GetDrives()) {
    [ordered]@{
        name = $drive.Name
        type = [string]$drive.DriveType
        ready = $drive.IsReady
    }
}
$report = [ordered]@{
    processArchitecture = [string][System.Runtime.InteropServices.RuntimeInformation]::ProcessArchitecture
    osArchitecture = [string][System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture
    osDescription = [System.Runtime.InteropServices.RuntimeInformation]::OSDescription
    powershellExecutable = $program
    powershellFileVersion = [System.Diagnostics.FileVersionInfo]::GetVersionInfo($program).FileVersion
    hostSystemRoot = $env:SystemRoot
    hostSystemDrive = $env:SystemDrive
    hostTemp = $env:TEMP
    hostTmp = $env:TMP
    hostDrives = @($drives)
    driveObservationBoundary = 'Host inventory only; actual PSEC policy paths and rejected drive are unobserved.'
    controls = $results.ToArray()
}
$report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $OutputDirectory 'arm-launch-controls.json') -Encoding utf8
$report | ConvertTo-Json -Depth 8
