$ErrorActionPreference = 'Stop'
$probeRoot = Join-Path $env:RUNNER_TEMP ('windows-startup-probe-' + [Guid]::NewGuid())
$workspaceProbe = Join-Path $env:GITHUB_WORKSPACE ('.cat/work/temp/windows-acl-probe-' + [Guid]::NewGuid())
New-Item -ItemType Directory -Path $probeRoot, $workspaceProbe | Out-Null

try {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    Write-Output ('Runner identity SID: ' + $identity.User.Value)
    foreach ($path in @($env:GITHUB_WORKSPACE, $env:RUNNER_TEMP, $workspaceProbe)) {
        $acl = Get-Acl -LiteralPath $path
        Write-Output ('ACL path: ' + $path)
        Write-Output ('ACL SDDL: ' + $acl.Sddl)
        $acl.Access | Select-Object IdentityReference, FileSystemRights, AccessControlType, IsInherited | Format-Table | Out-String | Write-Output
    }

    $templatePath = Join-Path $env:GITHUB_WORKSPACE '.github/diagnostics/workspace_root_test_launcher.bat.tpl'
    $template = [IO.File]::ReadAllText($templatePath)
    $launcher = Join-Path $probeRoot 'launcher.cmd'
    $binary = Join-Path $probeRoot 'stub.exe'
    $marker = Join-Path $probeRoot 'root/markers/BUILD.bazel'
    New-Item -ItemType Directory -Path (Split-Path -Parent $marker) | Out-Null
    [IO.File]::WriteAllText($marker, '')
    $source = Join-Path $probeRoot 'stub.cs'
    [IO.File]::WriteAllText($source, @'
using System;
public static class Stub {
    public static int Main(string[] args) {
        if (args.Length > 0 && args[0] == "--list") {
            Console.WriteLine("fixture::first: test");
            Console.WriteLine("fixture::second: test");
            return 0;
        }
        Console.WriteLine("fixture test binary started: " + string.Join(" ", args));
        return 0;
    }
}
'@)
    $compiler = Join-Path $env:WINDIR 'Microsoft.NET/Framework64/v4.0.30319/csc.exe'
    & $compiler /nologo /warnaserror /target:exe "/out:$binary" $source
    if ($LASTEXITCODE -ne 0) { throw "Native fixture compilation failed: $LASTEXITCODE" }
    $template = $template.Replace('__WORKSPACE_ROOT_MARKER__', 'probe/root/BUILD.bazel')
    $template = $template.Replace('__TEST_BIN__', 'probe/test.exe')
    $template = $template.Replace('__RUNFILE_ENV_EXPORTS__', '')
    $template = $template.Replace('__WORKSPACE_ROOT_SETUP__', '')
    if ($template -match '__[A-Z_]+__') { throw 'Unexpanded launcher placeholder' }
    [IO.File]::WriteAllText($launcher, $template.Replace("`r`n", "`n").Replace("`n", "`r`n"))

    $env:RUNFILES_DIR = ''
    $env:RUNFILES_MANIFEST_FILE = ''
    $env:TEST_SRCDIR = ''
    $env:TEST_WORKSPACE = ''
    $env:TEST_TMPDIR = $probeRoot
    $env:CODEX_BAZEL_TEST_SKIP_FILTERS = ''
    $env:TEST_TOTAL_SHARDS = ''
    $env:RULES_RUST_TEST_TOTAL_SHARDS = ''
    $env:TESTBRIDGE_TEST_ONLY = ''

    $runfiles = Join-Path $probeRoot 'runfiles'
    New-Item -ItemType Directory -Path (Join-Path $runfiles 'probe/root') | Out-Null
    Copy-Item -LiteralPath $marker -Destination (Join-Path $runfiles 'probe/root/BUILD.bazel')
    Copy-Item -LiteralPath $binary -Destination (Join-Path $runfiles 'probe/test.exe')
    $env:RUNFILES_DIR = $runfiles
    $watch = [Diagnostics.Stopwatch]::StartNew()
    & $env:ComSpec /d /c $launcher
    $exit = $LASTEXITCODE
    $watch.Stop()
    if ($exit -ne 0) { throw "Directory-runfiles control failed: $exit" }
    Write-Output ('Directory-runfiles control milliseconds: ' + $watch.ElapsedMilliseconds)
    $env:RUNFILES_DIR = ''

    $manifest = Join-Path $probeRoot 'MANIFEST'
    $env:RUNFILES_MANIFEST_FILE = $manifest
    $entries = [Collections.Generic.List[string]]::new()
    for ($index = 0; $index -lt 20000; $index++) {
        $entries.Add(('filler/{0:D5} {1}' -f $index, $binary))
    }
    $bindings = @("probe/root/BUILD.bazel $marker", "probe/test.exe $binary")
    foreach ($position in @('first', 'last')) {
        $lines = if ($position -eq 'first') { @($bindings) + $entries.ToArray() } else { $entries.ToArray() + @($bindings) }
        [IO.File]::WriteAllLines($manifest, [string[]]$lines)
        $watch = [Diagnostics.Stopwatch]::StartNew()
        & $env:ComSpec /d /c $launcher
        $exit = $LASTEXITCODE
        $watch.Stop()
        if ($exit -ne 0) { throw "Manifest-$position control failed: $exit" }
        Write-Output ('Manifest lookup result: ' + (@{
            position = $position
            entries = $lines.Count
            elapsedMilliseconds = $watch.ElapsedMilliseconds
        } | ConvertTo-Json -Compress))
    }

    $env:RUNFILES_MANIFEST_FILE = ''
    $env:RUNFILES_DIR = $runfiles
    foreach ($mode in @('skip-filter', 'sharded')) {
        $env:CODEX_BAZEL_TEST_SKIP_FILTERS = if ($mode -eq 'skip-filter') { 'fixture::excluded' } else { '' }
        $env:TEST_TOTAL_SHARDS = if ($mode -eq 'sharded') { '2' } else { '' }
        $env:TEST_SHARD_INDEX = if ($mode -eq 'sharded') { '0' } else { '' }
        $watch = [Diagnostics.Stopwatch]::StartNew()
        & $env:ComSpec /d /c $launcher
        $exit = $LASTEXITCODE
        $watch.Stop()
        if ($exit -ne 0) { throw "Selected-test $mode control failed: $exit" }
        Write-Output ('Selected-test launcher result: ' + (@{
            mode = $mode
            elapsedMilliseconds = $watch.ElapsedMilliseconds
        } | ConvertTo-Json -Compress))
    }
} finally {
    Remove-Item -LiteralPath $probeRoot, $workspaceProbe -Recurse -Force
    foreach ($path in @($probeRoot, $workspaceProbe)) {
        if (Test-Path -LiteralPath $path) { throw "Diagnostic cleanup failed: $path" }
    }
}

& (Join-Path $env:GITHUB_WORKSPACE '.github/diagnostics/windows-delete-access-probe.ps1')
