# Rewrites upstream's setup-dev-drive.ps1 so that the Dev Drive it provisions fills the runner-temp drive.
#
# Upstream's setup-dev-drive.ps1 provisions a fixed 64 GB Dev Drive when D: is not one. Upstream runs its Windows Bazel
# jobs on larger runners whose D: is already a Dev Drive; on windows-2025 the native MSVC tests' 523 targets, gnullvm
# test shard 3 and the Windows clippy lint filled 64 GB ("LLVM ERROR: IO failure on output stream: No space left on
# device"). The VHD is dynamic and lives on the runner-temp drive (D:, about 150 GB free on windows-2025), so this sizes
# it from that drive's free space instead.
#
# In run 45285935516 a 78 GB Dev Drive filled with 67.5 GB of execroot, 8.9 GB of repository contents cache and 2.2 GB
# of repository cache, while the 36 GB that the sizing then kept back on D: stayed unused: the files outside the VHD grew
# by under 2 GB and the page file never grew. So the sizing keeps back 4 GB for those files and nothing for page file
# growth. The page file and the VHD each grow only into what the other leaves, so one that grows after D: has filled
# fails, as os error 1455 or as a write error on the Dev Drive. Add the page file before running this.
#
# On windows-2025 fsutil returns zero even for a regular volume, so upstream's probe would take a regular D: for a Dev
# Drive. When the script still has that probe, this replaces it with one that reads fsutil's verdict; a script that the
# downstream volume-state patch already fixed is left as it is.
param(
  # Upstream's .github/scripts/setup-dev-drive.ps1 in the checked-out source.
  [Parameter(Mandatory = $true)][string]$SetupScript
)

$ErrorActionPreference = 'Stop'

$tempDrive = (Get-Item -LiteralPath $env:RUNNER_TEMP).PSDrive
$freeGb = [math]::Floor($tempDrive.Free / 1GB)
$sizeGb = [math]::Min($freeGb - 4, 240)
Write-Output "The runner-temp drive has $freeGb GB free."
if ($sizeGb -lt 1) { throw 'Insufficient runner-temp space to provision a Dev Drive.' }
Write-Output "A provisioned Dev Drive will be $sizeGb GB."
$text = [System.IO.File]::ReadAllText($SetupScript)
if ($text -notmatch '\$SizeBytes = 64GB') { throw 'upstream setup-dev-drive.ps1 no longer sets a 64GB size' }
$oldProbe = '(?m)^    & fsutil devdrv query \$Drive \*> \$null\r?\n    return \$LASTEXITCODE -eq 0\r?$'
$oldProbeCount = [regex]::Matches($text, $oldProbe).Count
if ($oldProbeCount -eq 1) {
  $newProbe = @'
$state = & fsutil devdrv query $Drive 2>$null
return ($LASTEXITCODE -eq 0) -and [bool]($state -match '^(?:This is a trusted developer volume|The volume is formatted as a developer volume but is not trusted on this machine)\.$')
'@
  $text = [regex]::Replace($text, $oldProbe, [System.Text.RegularExpressions.MatchEvaluator]{
    param($match)
    ($newProbe -split '\r?\n' | ForEach-Object { '    ' + $_ }) -join "`n"
  })
} elseif ($oldProbeCount -ne 0 -or
    !$text.Contains('$volumeFlags = $script:DevDriveVolumeStateType::QueryVolumeFlags($Drive)') -or
    !$text.Contains('$isDevVolume = ($volumeFlags -band $devVolumeMask) -gt 0')) {
  throw 'setup-dev-drive.ps1 has neither the pinned upstream probe nor the downstream volume-state probe'
}
[System.IO.File]::WriteAllText($SetupScript, ($text -replace '\$SizeBytes = 64GB', "`$SizeBytes = ${sizeGb}GB"))
