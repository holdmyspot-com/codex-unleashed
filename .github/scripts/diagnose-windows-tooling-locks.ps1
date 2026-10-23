param(
    [Parameter(Mandatory = $true)][string]$WorkspaceRoot,
    [Parameter(Mandatory = $true)][string]$OutputDirectory
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$temporaryRoot = [IO.Path]::GetFullPath((Join-Path $WorkspaceRoot '.cat/work/temp/build-caches/maven/tmp'))
$temporaryPrefix = $temporaryRoot.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
$failures = [Collections.Generic.List[object]]::new()
$candidates = @()
$enumerationComplete = $true
try {
    if (Test-Path -LiteralPath $temporaryRoot) {
        $candidates = @(Get-ChildItem -LiteralPath $temporaryRoot -Filter 'verify.dll' -File -Recurse |
            Where-Object { $_.FullName.StartsWith($temporaryPrefix, [StringComparison]::OrdinalIgnoreCase) } |
            ForEach-Object { $_.FullName })
    }
}
catch {
    $enumerationComplete = $false
    $failures.Add([ordered]@{ operation = 'enumerate-verify-dll'; message = $_.Exception.Message })
}

$deniedPaths = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
foreach ($module in @('tooling-cli', 'tooling-distribution')) {
    $reports = Join-Path $WorkspaceRoot ".cat/work/temp/build-caches/maven/target/$module/surefire-reports"
    try {
        if (Test-Path -LiteralPath $reports) {
            Get-ChildItem -LiteralPath $reports -Filter '*.txt' -File | ForEach-Object {
                Get-Content -LiteralPath $_.FullName | ForEach-Object {
                    if ($_ -match 'java\.nio\.file\.AccessDeniedException:\s*(.+)$') {
                        $denied = [IO.Path]::GetFullPath($Matches[1].Trim())
                        if ($denied.StartsWith($temporaryPrefix, [StringComparison]::OrdinalIgnoreCase)) {
                            $null = $deniedPaths.Add($denied)
                        }
                    }
                }
            }
        }
    }
    catch {
        $failures.Add([ordered]@{ operation = 'read-surefire-denials'; module = $module; message = $_.Exception.Message })
    }
}

$candidatePaths = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
foreach ($candidate in $candidates) {
    $null = $candidatePaths.Add($candidate)
}
$nativeProcesses = @{}
try {
    foreach ($nativeProcess in Get-CimInstance -ClassName Win32_Process) {
        $nativeProcesses[[int]$nativeProcess.ProcessId] = $nativeProcess
    }
}
catch {
    $failures.Add([ordered]@{ operation = 'enumerate-native-processes'; message = $_.Exception.Message })
}

$matchesByProcess = [Collections.Generic.List[object]]::new()
try {
    foreach ($process in Get-Process) {
        $processId = $process.Id
        $processName = $null
        try {
            $processName = $process.ProcessName
            $matchedModules = [Collections.Generic.List[object]]::new()
            foreach ($loadedModule in $process.Modules) {
                try {
                    $filename = $loadedModule.FileName
                    if ($candidatePaths.Contains($filename)) {
                        $matchedModules.Add([ordered]@{ filename = $filename; module_name = $loadedModule.ModuleName })
                    }
                }
                catch {
                    $failures.Add([ordered]@{ operation = 'inspect-module'; process_id = $processId; process_name = $processName; message = $_.Exception.Message })
                }
            }
            if ($matchedModules.Count -gt 0) {
                $native = $nativeProcesses[$processId]
                $correlation = $null
                if ($null -ne $native) {
                    $correlation = [ordered]@{
                        process_id = $native.ProcessId
                        parent_process_id = $native.ParentProcessId
                        creation_time = $native.CreationDate
                        executable_path = $native.ExecutablePath
                        command_line = $native.CommandLine
                    }
                }
                else {
                    $failures.Add([ordered]@{ operation = 'correlate-native-process'; process_id = $processId; message = 'No corresponding process in the CIM snapshot; it may have exited or inspection may be unavailable.' })
                }
                $matchesByProcess.Add([ordered]@{ process_id = $processId; process_name = $processName; modules = @($matchedModules.ToArray()); native_process = $correlation })
            }
        }
        catch {
            $failures.Add([ordered]@{ operation = 'inspect-process-modules'; process_id = $processId; process_name = $processName; message = $_.Exception.Message })
        }
        finally {
            $process.Dispose()
        }
    }
}
catch {
    $failures.Add([ordered]@{ operation = 'enumerate-processes'; message = $_.Exception.Message })
}

$observation = 'Remaining verify.dll candidates were enumerated; absence of process matches does not prove absence of locks.'
if (-not $enumerationComplete) {
    $observation = 'Candidate enumeration failed; remaining verify.dll presence is unknown.'
}
elseif ($candidates.Count -eq 0) {
    $observation = 'No verify.dll remains beneath the Maven temporary root at inspection time. This does not establish why deletion failed earlier.'
}
$report = [ordered]@{
    temporary_root = $temporaryRoot
    enumeration_complete = $enumerationComplete
    observation = $observation
    reported_denied_paths = @($deniedPaths | Sort-Object)
    remaining_verify_dll_paths = $candidates
    matching_processes = @($matchesByProcess.ToArray())
    inspection_failures = @($failures.ToArray())
    limitations = 'Process and CIM snapshots are not atomic. Module enumeration can fail or miss processes that exit. Matching a module identifies a mapping, not proof that it caused deletion denial; no matches never proves that no lock exists.'
}
$null = New-Item -ItemType Directory -Path $OutputDirectory -Force
$report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $OutputDirectory 'runtime-dll-processes.json') -Encoding utf8
Write-Output $observation
