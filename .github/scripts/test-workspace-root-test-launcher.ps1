param(
    [Parameter(Mandatory = $true)][string]$TemplatePath,
    [Parameter(Mandatory = $true)][string]$WorkDirectory,
    [Parameter(Mandatory = $true)][string]$ReportPath
)

$ErrorActionPreference = 'Stop'
$encoding = [System.Text.UTF8Encoding]::new($false)
$template = [System.IO.File]::ReadAllText((Resolve-Path $TemplatePath)).Replace("`r`n", "`n")
$root = Join-Path $WorkDirectory ("launcher probe " + [guid]::NewGuid().ToString('N'))
$results = [System.Collections.Generic.List[object]]::new()

try {
    $workspace = Join-Path $root 'workspace'
    $core = Join-Path $workspace 'codex-rs/core'
    New-Item -ItemType Directory -Path $core -Force | Out-Null
    $marker = Join-Path $core 'Cargo.toml'
    [System.IO.File]::WriteAllText($marker, '', $encoding)
    $helperPath = Join-Path $root 'helper resource.txt'
    [System.IO.File]::WriteAllText($helperPath, '', $encoding)
    $binary = Join-Path $root 'test binary.cmd'
    $manifest = Join-Path $root 'MANIFEST'

    foreach ($helperCount in @(0, 3, 12)) {
        $exports = [System.Collections.Generic.List[string]]::new()
        $entries = [System.Collections.Generic.List[string]]::new()
        $payload = [System.Collections.Generic.List[string]]::new()
        $entries.Add("_main/codex-rs/core/Cargo.toml $marker")
        $entries.Add("_main/codex-rs/app-server/app-server-unit-tests-bin.exe $binary")
        $payload.Add('@echo off')
        for ($helper = 0; $helper -lt $helperCount; ++$helper) {
            $logical = "codex-rs/fixture-helper-$helper/fixture-helper-$helper.exe"
            $variable = "CARGO_BIN_EXE_fixture_helper_$helper"
            $exports.Add("call :resolve_runfile `"$logical`"")
            $exports.Add('if errorlevel 1 exit /b 1')
            $exports.Add("set `"$variable=!resolve_runfile_result!`"")
            $entries.Add("_main/$logical $helperPath")
            $payload.Add("if not `"%$variable%`"==`"$helperPath`" exit /b 91")
        }
        $payload.Add('echo launcher-sentinel')
        $payload.Add('exit /b 37')
        [System.IO.File]::WriteAllText($binary, ($payload -join "`r`n") + "`r`n", $encoding)
        [System.IO.File]::WriteAllText($manifest, ($entries -join "`r`n") + "`r`n", $encoding)
        $expanded = $template.Replace('__WORKSPACE_ROOT_MARKER__', 'codex-rs/core/Cargo.toml').
            Replace('__TEST_BIN__', 'codex-rs/app-server/app-server-unit-tests-bin.exe').
            Replace('__RUNFILE_ENV_EXPORTS__', ($exports -join "`n")).
            Replace('__WORKSPACE_ROOT_SETUP__', 'cd /d "%workspace_root%" || exit /b 1')

        foreach ($form in @('LF', 'CRLF')) {
            $launcher = Join-Path $root "launcher-$helperCount-$form.bat"
            $contents = if ($form -eq 'CRLF') { $expanded.Replace("`n", "`r`n") } else { $expanded }
            [System.IO.File]::WriteAllText($launcher, $contents, $encoding)
            $start = [System.Diagnostics.ProcessStartInfo]::new($env:ComSpec)
            $start.UseShellExecute = $false
            $start.RedirectStandardOutput = $true
            $start.RedirectStandardError = $true
            $start.ArgumentList.Add('/d')
            $start.ArgumentList.Add('/c')
            $start.ArgumentList.Add($launcher)
            $start.Environment['RUNFILES_MANIFEST_FILE'] = $manifest
            $start.Environment['TEST_WORKSPACE'] = '_main'
            foreach ($key in @('RUNFILES_DIR', 'TEST_SRCDIR', 'RULES_RUST_TEST_TOTAL_SHARDS',
                'TEST_TOTAL_SHARDS', 'TESTBRIDGE_TEST_ONLY', 'CODEX_BAZEL_TEST_SKIP_FILTERS')) {
                $start.Environment.Remove($key) | Out-Null
            }
            $child = [System.Diagnostics.Process]::Start($start)
            try {
                $stdout = $child.StandardOutput.ReadToEndAsync()
                $stderr = $child.StandardError.ReadToEndAsync()
                if (-not $child.WaitForExit(30000)) {
                    $child.Kill($true)
                    $child.WaitForExit()
                    throw "Native launcher probe timed out: helpers=$helperCount form=$form"
                }
                $output = $stdout.GetAwaiter().GetResult()
                $diagnostic = $stderr.GetAwaiter().GetResult()
                $passed = $child.ExitCode -eq 37 -and $output.Trim() -eq 'launcher-sentinel'
                $result = [pscustomobject]@{
                    helpers = $helperCount; form = $form; bytes = [System.IO.File]::ReadAllBytes($launcher).Length
                    status = $child.ExitCode; passed = $passed; stdout = $output; stderr = $diagnostic
                }
                $results.Add($result)
                $result | ConvertTo-Json -Compress | Write-Output
            }
            finally {
                $child.Dispose()
            }
        }
    }
}
finally {
    [System.IO.File]::WriteAllText($ReportPath, (ConvertTo-Json -InputObject $results.ToArray() -Depth 4), $encoding)
    if (Test-Path -LiteralPath $root) {
        Remove-Item -LiteralPath $root -Recurse
    }
}

$candidateFailures = @($results | Where-Object { $_.form -eq 'CRLF' -and -not $_.passed })
if ($candidateFailures.Count -gt 0) {
    throw "CRLF candidate fails in $($candidateFailures.Count) native launcher cases; inspect the report."
}
$baselineFailures = @($results | Where-Object { $_.form -eq 'LF' -and -not $_.passed })
Write-Output "LF failures: $($baselineFailures.Count); CRLF failures: $($candidateFailures.Count)."
