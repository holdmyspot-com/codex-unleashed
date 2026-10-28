param([Parameter(Mandatory = $true)][string]$TestFilter)

$ErrorActionPreference = 'Stop'
$evidence = Join-Path $env:FOCUSED_RUNTIME 'evidence'
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
@'
$ErrorActionPreference = 'Stop'
$settings = Get-Content (Join-Path $PSScriptRoot 'runtime-env.json') -Raw | ConvertFrom-Json
foreach ($property in $settings.PSObject.Properties) {
    [Environment]::SetEnvironmentVariable($property.Name, $property.Value, 'Process')
}
Set-Location (Join-Path $env:CODEX_REPO_ROOT 'codex-rs')
& (Join-Path $PSScriptRoot 'cargo-nextest.exe') nextest run `
    --binaries-metadata (Join-Path $PSScriptRoot 'binaries.json') `
    --cargo-metadata (Join-Path $PSScriptRoot 'cargo.json') `
    --no-fail-fast --test-threads 8 --success-output immediate `
    -E $env:CODEX_RUNTIME_TEST_FILTER
exit $LASTEXITCODE
'@ | Set-Content $childScript

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
    $credential = [pscredential]::new($username, $password)
    $process = Start-Process -FilePath (Get-Command powershell.exe).Source -Credential $credential `
        -WorkingDirectory $source -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', ('"' + $childScript + '"')) `
        -RedirectStandardOutput (Join-Path $evidence 'runtime-stdout.log') `
        -RedirectStandardError (Join-Path $evidence 'runtime-stderr.log') -Wait -PassThru
    @{ childExit = $process.ExitCode; sid = $user.SID.Value } |
        ConvertTo-Json | Set-Content (Join-Path $evidence 'runtime-launch.json')
    $output = @(
        Get-Content (Join-Path $evidence 'runtime-stdout.log') -Raw
        Get-Content (Join-Path $evidence 'runtime-stderr.log') -Raw
    ) -join "`n"
    $output | Set-Content (Join-Path $evidence 'test.log')
    Write-Output $output

    $testExit = $process.ExitCode
    if ($output.Contains('requires a non-elevated Windows runner')) {
        $testExit = 78
        [Console]::Error.WriteLine('The test returned before exercising its non-elevated Windows assertions.')
    }
    if (($testExit -eq 0) -and ($output -notmatch '\b1 passed\b.*\b0 skipped\b')) {
        $testExit = 78
        [Console]::Error.WriteLine('The runtime result does not report exactly one passing test with zero skipped tests.')
    }
    $testExit | Set-Content (Join-Path $evidence 'test-exit')
} finally {
    $username = $env:COMPUTERNAME + '\' + $accountName
    $remaining = Get-Process -IncludeUserName | Where-Object UserName -eq $username
    $remaining | Stop-Process -Force
    $remaining | ForEach-Object { $_.WaitForExit() }
    Remove-LocalUser -Name $accountName
    Get-CimInstance Win32_UserProfile | Where-Object SID -eq $user.SID.Value | Remove-CimInstance
    'STANDARD-USER-CLEANUP-DONE' | Set-Content (Join-Path $evidence 'runtime-cleanup.txt')
}
exit $testExit
