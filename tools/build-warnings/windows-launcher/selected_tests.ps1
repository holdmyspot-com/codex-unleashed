param([Parameter(Mandatory = $true)][string]$TempRoot)

$ErrorActionPreference = 'Stop'
$root = Join-Path $TempRoot ('selected-launcher-' + [Guid]::NewGuid())
New-Item -ItemType Directory -Path $root | Out-Null
try {
    $source = Join-Path $root 'fixture.cs'
    $binary = Join-Path $root 'fixture.exe'
    [IO.File]::WriteAllText($source, @'
using System;
using System.IO;
public static class Fixture {
    public static int Main(string[] args) {
        if (args.Length > 0 && args[0] == "--list") {
            foreach (string name in File.ReadAllLines(Environment.GetEnvironmentVariable("FIXTURE_NAMES")))
                Console.WriteLine(name + ": test");
            return 0;
        }
        int result = 0;
        foreach (string name in args) {
            if (name == "--exact") continue;
            File.AppendAllText(Environment.GetEnvironmentVariable("FIXTURE_OUTPUT"), name + Environment.NewLine);
            if (name == "fixture::fails") result = 1;
        }
        return result;
    }
}
'@)
    $compiler = Join-Path $env:WINDIR 'Microsoft.NET/Framework64/v4.0.30319/csc.exe'
    & $compiler /nologo /warnaserror /target:exe "/out:$binary" $source
    if ($LASTEXITCODE -ne 0) { throw "Fixture compilation failed: $LASTEXITCODE" }

    $runfiles = Join-Path $root 'runfiles'
    New-Item -ItemType Directory -Path (Join-Path $runfiles 'probe/root') | Out-Null
    [IO.File]::WriteAllText((Join-Path $runfiles 'probe/root/BUILD.bazel'), '')
    Copy-Item -LiteralPath $binary -Destination (Join-Path $runfiles 'fixture.exe')
    $template = [IO.File]::ReadAllText((Join-Path $PSScriptRoot '../../../workspace_root_test_launcher.bat.tpl'))
    $template = $template.Replace('__WORKSPACE_ROOT_MARKER__', 'probe/root/BUILD.bazel').Replace('__TEST_BIN__', 'fixture.exe').Replace('__RUNFILE_ENV_EXPORTS__', '').Replace('__WORKSPACE_ROOT_SETUP__', '')
    $launcher = Join-Path $root 'launcher.cmd'
    [IO.File]::WriteAllText($launcher, $template.Replace("`r`n", "`n").Replace("`n", "`r`n"))
    $env:RUNFILES_DIR = $runfiles
    $env:TEST_SRCDIR = ''
    $env:TEST_WORKSPACE = ''
    $env:TESTBRIDGE_TEST_ONLY = ''
    $env:RULES_RUST_TEST_TOTAL_SHARDS = ''
    $env:RULES_RUST_TEST_SHARD_INDEX = ''
    $env:TEST_SHARD_STATUS_FILE = ''
    $env:TEST_TMPDIR = $root
    $env:FIXTURE_NAMES = Join-Path $root 'names.txt'
    $env:FIXTURE_OUTPUT = Join-Path $root 'output.txt'
    $env:CODEX_BAZEL_TEST_SKIP_FILTERS = 'fixture::excluded'
    $names = @('fixture::excluded', 'fixture::first', 'fixture::second')
    $names += @(0..99 | ForEach-Object { 'fixture::long_' + ('x' * 100) + $_ })
    [IO.File]::WriteAllLines($env:FIXTURE_NAMES, [string[]]$names)
    $expected = @($names | Where-Object { $_ -ne 'fixture::excluded' } | Sort-Object)

    foreach ($mode in @('unsharded', 'sharded')) {
        $combined = @()
        $shards = if ($mode -eq 'sharded') { 2 } else { 1 }
        $env:TEST_TOTAL_SHARDS = if ($mode -eq 'sharded') { '2' } else { '' }
        for ($index = 0; $index -lt $shards; $index++) {
            $env:TEST_SHARD_INDEX = [string]$index
            [IO.File]::WriteAllText($env:FIXTURE_OUTPUT, '')
            & $env:ComSpec /d /c $launcher
            if ($LASTEXITCODE -ne 0) { throw "$mode launcher failed: $LASTEXITCODE" }
            $combined += [IO.File]::ReadAllLines($env:FIXTURE_OUTPUT)
        }
        if (Compare-Object $expected @($combined | Sort-Object)) {
            throw "$mode launcher omitted, duplicated, or incorrectly selected tests"
        }
    }

    $env:TEST_TOTAL_SHARDS = ''
    [IO.File]::WriteAllLines($env:FIXTURE_NAMES, [string[]]@('fixture::fails'))
    & $env:ComSpec /d /c $launcher
    if ($LASTEXITCODE -eq 0) { throw 'Launcher discarded the failing test exit status' }
    Write-Output 'Selected launcher preserves filtering, shard coverage, long argument batching, and failure status.'
} finally {
    Remove-Item -LiteralPath $root -Recurse -Force
    if (Test-Path -LiteralPath $root) { throw "Fixture cleanup failed: $root" }
}

exit 0
