# Experiment probe for the windows-sandbox-rs legacy_workspace_write_delete failure.
# Modes:
#   Dump  - identity, volumes and ACLs of the checkout and its parents.
#   Arms  - runs the failing test binary directly with INSTA_WORKSPACE_ROOT on differently-ACLed roots.
param(
  [Parameter(Mandatory)][ValidateSet('Dump', 'Arms')][string]$Mode,
  [Parameter(Mandatory)][string]$Evidence,
  [Parameter(Mandatory)][string]$Checkout,
  [string]$TestExe = ''
)
$ErrorActionPreference = 'Continue'
New-Item -ItemType Directory -Force -Path $Evidence | Out-Null

function Show-Acl([string]$Path) {
  Write-Output "--- icacls $Path"
  icacls $Path
  if (Test-Path -LiteralPath $Path) {
    $acl = Get-Acl -LiteralPath $Path
    Write-Output "owner: $($acl.Owner)"
    Write-Output "sddl: $($acl.Sddl)"
  }
}

if ($Mode -eq 'Dump') {
  Write-Output '=== identity'
  whoami /user
  whoami /groups
  Write-Output '=== volumes'
  foreach ($drive in 'C:', 'D:', $env:CI_BUILD_ROOT) {
    if ($drive) {
      Write-Output "--- $drive"
      fsutil fsinfo volumeinfo $drive
      fsutil devdrv query $drive
    }
  }
  Write-Output '=== directory ACLs'
  $paths = @(
    'C:\', 'C:\a', 'C:\Users', 'D:\', 'D:\a', (Split-Path -Parent $Checkout), $Checkout,
    (Join-Path $Checkout 'codex-rs'), $env:RUNNER_TEMP, $env:TEMP
  ) | Where-Object { $_ -and (Test-Path -LiteralPath $_) } | Select-Object -Unique
  foreach ($path in $paths) { Show-Acl $path }
  Write-Output '=== a directory created as the test does (tempfile::TempDir::new_in the checkout)'
  $probe = Join-Path (Join-Path $Checkout 'codex-rs') '.tmpAclProbe'
  New-Item -ItemType Directory -Force -Path $probe | Out-Null
  Show-Acl $probe
  Remove-Item -LiteralPath $probe -Force
  return
}

$script:results = @()
function Invoke-Arm([string]$Name, [string]$Root, [scriptblock]$Prepare) {
  Write-Output "::group::arm $Name root=$Root"
  New-Item -ItemType Directory -Force -Path $Root | Out-Null
  if ($Prepare) { & $Prepare }
  Show-Acl $Root
  $child = Join-Path $Root '.tmpChildProbe'
  New-Item -ItemType Directory -Force -Path $child | Out-Null
  Show-Acl $child
  Remove-Item -LiteralPath $child -Force
  $env:INSTA_WORKSPACE_ROOT = $Root
  $log = Join-Path $Evidence "arm-$Name.log"
  & $TestExe --exact --nocapture 'unified_exec::tests::legacy_workspace_write_delete_is_limited_to_writable_roots' *>&1 |
    Tee-Object -FilePath $log
  $code = $LASTEXITCODE
  Write-Output "ARM_RESULT name=$Name exit=$code"
  $script:results += "$Name exit=$code"
  Write-Output '::endgroup::'
}

$probeRoot = 'D:\a\sandbox-acl-probe'
$cRoot = 'C:\sandbox-acl-probe'
Invoke-Arm 'checkout-d' (Join-Path $Checkout 'codex-rs') $null
Invoke-Arm 'plain-d' (Join-Path $probeRoot 'plain') $null
Invoke-Arm 'plain-c' (Join-Path $cRoot 'plain') $null
Invoke-Arm 'inheritance-removed-d' (Join-Path $probeRoot 'stripped') {
  icacls (Join-Path $probeRoot 'stripped') /inheritance:r
  icacls (Join-Path $probeRoot 'stripped') /grant:r '*S-1-5-32-544:(OI)(CI)F' '*S-1-5-18:(OI)(CI)F'
}
# Bisect: copy the inherited ACEs onto a fresh directory, then remove one trustee at a time.
$template = Join-Path $probeRoot 'template'
New-Item -ItemType Directory -Force -Path $template | Out-Null
icacls $template /inheritance:d | Out-Null
$trustees = (Get-Acl -LiteralPath $template).Access | ForEach-Object { $_.IdentityReference.Value } | Select-Object -Unique
Write-Output "trustees on a D: directory: $($trustees -join '; ')"
$index = 0
foreach ($trustee in $trustees) {
  if ($trustee -in 'NT AUTHORITY\SYSTEM', 'BUILTIN\Administrators') { continue }
  $index++
  $root = Join-Path $probeRoot "minus-$index"
  Invoke-Arm "minus-$index-d" $root {
    icacls $root /inheritance:d | Out-Null
    icacls $root /remove:g $trustee
  }
  $script:results += "  (minus-$index removed $trustee)"
}
Write-Output '=== ARM SUMMARY'
$script:results | ForEach-Object { Write-Output $_ }
$script:results | Set-Content -LiteralPath (Join-Path $Evidence 'arm-summary.txt')
