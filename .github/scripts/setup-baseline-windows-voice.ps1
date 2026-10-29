<#
.SYNOPSIS
Install pinned Windows voice build prerequisites and retain installation evidence.
.DESCRIPTION
Run with PowerShell on Windows from the pinned upstream checkout after setup-bazel-ci
prepares MSVC. Requires SystemRoot, a host processor architecture, RUNNER_TEMP, and
GITHUB_ENV. Reads .github/scripts/voice-cygwin-snapshot.json from that checkout,
downloads its binary and source archives from the upstream openai/codex release,
and rejects any archive whose byte count or SHA-256 digest differs from the manifest.
Runs the checkout's setup-voice-windows.ps1 for x86_64-pc-windows-msvc, then exports
VOICE_WINDOWS_SYSTEM_ROOT and VOICE_WINDOWS_HOST_ARCH through GITHUB_ENV.
Copies the manifest to EvidenceDirectory and attempts to copy available tool evidence
there even if installation raises a terminating error. Terminating download,
verification, installation, and evidence-copy errors stop the script. Downloads remain
under RUNNER_TEMP.
.PARAMETER EvidenceDirectory
Destination directory created if needed for the manifest and installed-tools evidence.
#>
param(
    [Parameter(Mandatory = $true)][ValidateNotNullOrEmpty()][string]$EvidenceDirectory
)

$ErrorActionPreference = "Stop"
if (-not $IsWindows) { throw "Windows voice prerequisites require a Windows host" }
if (-not $env:SystemRoot) { throw "Windows voice prerequisites require SystemRoot" }
$HostArchitecture = $env:PROCESSOR_ARCHITEW6432
if (-not $HostArchitecture) { $HostArchitecture = $env:PROCESSOR_ARCHITECTURE }
if (-not $HostArchitecture) { throw "Windows voice prerequisites require a host processor architecture" }

$ManifestPath = Join-Path $PWD ".github/scripts/voice-cygwin-snapshot.json"
$Manifest = Get-Content -Raw $ManifestPath | ConvertFrom-Json
$Downloads = Join-Path $env:RUNNER_TEMP "baseline-windows-voice-downloads"
New-Item -ItemType Directory -Force -Path $Downloads, $EvidenceDirectory | Out-Null
Copy-Item $ManifestPath (Join-Path $EvidenceDirectory "cygwin-snapshot.json")

# Use the upstream release, rather than looking for its assets in the fork.
foreach ($Archive in @($Manifest.archive, $Manifest.sourceArchive)) {
    $Destination = Join-Path $Downloads $Archive.name
    $Url = "https://github.com/openai/codex/releases/download/$($Manifest.archive.tag)/$($Archive.name)"
    Invoke-WebRequest -Uri $Url -OutFile $Destination
    if ((Get-Item $Destination).Length -ne $Archive.bytes) {
        throw "Pinned Cygwin archive size mismatch: $($Archive.name)"
    }
    $Digest = (Get-FileHash $Destination -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($Digest -ne $Archive.sha256) {
        throw "Pinned Cygwin archive digest mismatch: $($Archive.name)"
    }
}

try {
    & ./.github/scripts/setup-voice-windows.ps1 `
        -Target "x86_64-pc-windows-msvc" `
        -SnapshotArchive (Join-Path $Downloads $Manifest.archive.name)
} finally {
    $ToolEvidence = Join-Path $env:RUNNER_TEMP "voice-windows-build-tools/evidence"
    if (Test-Path $ToolEvidence) {
        Copy-Item -Recurse $ToolEvidence (Join-Path $EvidenceDirectory "installed-tools")
    }
}

"VOICE_WINDOWS_SYSTEM_ROOT=$env:SystemRoot" | Out-File $env:GITHUB_ENV -Encoding utf8 -Append
"VOICE_WINDOWS_HOST_ARCH=$HostArchitecture" | Out-File $env:GITHUB_ENV -Encoding utf8 -Append
