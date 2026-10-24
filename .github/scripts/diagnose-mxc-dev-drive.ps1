$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [Security.Principal.WindowsPrincipal]::new($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
  throw 'The volume attachment control requires an elevated runner token.'
}
$drive = $env:CI_BUILD_ROOT
if ($env:RUNNER_ENVIRONMENT -ne 'github-hosted' -or $drive -notmatch '^[A-Za-z]:$' -or $drive -eq $env:SystemDrive) {
  throw "Expected a non-system build volume on an ephemeral GitHub-hosted runner; received '$drive'."
}
$volume = Get-Volume -DriveLetter $drive.Substring(0, 1) -ErrorAction Stop
if ($volume.FileSystem -ne 'ReFS') {
  throw "The provisioned build volume '$drive' uses '$($volume.FileSystem)', not ReFS."
}
$volume | Format-List DriveLetter, FileSystem, FileSystemLabel, UniqueId, HealthStatus

& fsutil.exe devdrv query
$globalQueryExit = $LASTEXITCODE
& fsutil.exe devdrv query $drive
$volumeQueryExit = $LASTEXITCODE
$beforeInstances = @(& fltmc.exe instances -v $drive 2>&1)
$beforeInstancesExit = $LASTEXITCODE
$beforeInstances | Write-Output
Write-Output "Baseline query statuses: global=$globalQueryExit volume=$volumeQueryExit instances=$beforeInstancesExit"
if ($globalQueryExit -ne 0 -or $volumeQueryExit -ne 0 -or $beforeInstancesExit -ne 0) {
  throw 'A baseline volume or filter query failed; the attachment comparison cannot run.'
}
if ($beforeInstances -match '^\s*bfs\s+') {
  throw 'BFS is already attached; this comparison requires a baseline without BFS.'
}

$filterVolumes = @(& fltmc.exe volumes 2>&1)
$filterVolumesExit = $LASTEXITCODE
$filterVolumes | Write-Output
$filters = @(& fltmc.exe filters 2>&1)
$filtersExit = $LASTEXITCODE
$filters | Write-Output
& fltmc.exe instances -f bfs
$bfsInstancesExit = $LASTEXITCODE
& mountvol.exe ($drive + '\') /L
$mountQueryExit = $LASTEXITCODE
Write-Output "Filter inventory statuses: volumes=$filterVolumesExit filters=$filtersExit bfs=$bfsInstancesExit mount=$mountQueryExit"
if ($filterVolumesExit -ne 0 -or $filtersExit -ne 0 -or $bfsInstancesExit -ne 0 -or $mountQueryExit -ne 0 -or -not ($filters -match '^\s*bfs\s+')) {
  throw 'The filter inventory failed or BFS is not loaded; the eligibility experiment cannot run.'
}

$vhdPath = Join-Path $env:RUNNER_TEMP 'codex-dev-drive.vhdx'
$vhd = Get-VHD -Path $vhdPath -ErrorAction Stop
$partition = Get-Partition -DriveLetter $volume.DriveLetter -ErrorAction Stop
if ($volume.FileSystemLabel -ne 'CodexDevDrive' -or -not $vhd.Attached -or $vhd.DiskNumber -ne $partition.DiskNumber) {
  throw 'The build volume is not the VHD created by this job; refusing to change its filter policy or mount state.'
}
$volumeId = $volume.UniqueId
& fsutil.exe devdrv setFiltersAllowed /volume $drive bfs
$allowExit = $LASTEXITCODE
Write-Output "Owned-volume BFS eligibility exit status: $allowExit"
if ($allowExit -ne 0) {
  throw 'The volume-local BFS allowance failed; no global policy change or fallback is permitted.'
}
Dismount-VHD -Path $vhdPath -ErrorAction Stop
Mount-VHD -Path $vhdPath -ErrorAction Stop
$remountedVolume = Get-Volume -DriveLetter $volume.DriveLetter -ErrorAction Stop
if ($remountedVolume.UniqueId -ne $volumeId) {
  throw 'The remounted drive does not identify the original job-owned volume.'
}

$eligibleInstances = @(& fltmc.exe instances -v $drive 2>&1)
$eligibleInstancesExit = $LASTEXITCODE
$eligibleInstances | Write-Output
if ($eligibleInstancesExit -ne 0) {
  throw 'The post-eligibility instance query failed; no attachment attempt is permitted.'
}
$attachExit = 0
if ($eligibleInstances -match '^\s*bfs\s+') {
  Write-Output 'BFS attached during the owned-volume remount; no manual attachment is needed.'
} else {
  $attachOutput = @(& fltmc.exe attach bfs $drive 2>&1)
  $attachExit = $LASTEXITCODE
  $attachOutput | Write-Output
  Write-Output "Single eligible-volume BFS attachment exit status: $attachExit"
}

$afterInstances = @(& fltmc.exe instances -v $drive 2>&1)
$afterInstancesExit = $LASTEXITCODE
$afterInstances | Write-Output
& fsutil.exe devdrv query $drive
$afterVolumeQueryExit = $LASTEXITCODE
Write-Output "Post-attachment query statuses: instances=$afterInstancesExit volume=$afterVolumeQueryExit"
if ($attachExit -ne 0 -or $afterInstancesExit -ne 0 -or $afterVolumeQueryExit -ne 0) {
  throw 'The owned-volume BFS eligibility control failed; no retries or broader policy changes are permitted.'
}
if (-not ($afterInstances -match '^\s*bfs\s+')) {
  throw "The attachment command succeeded but BFS is absent from the '$drive' instance listing."
}
Write-Output 'BFS attachment availability is verified; secure sandbox behavior remains unverified.'
