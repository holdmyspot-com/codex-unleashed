# Adds the page file that the Windows Bazel jobs need, split between C: and D: so that it leaves D: to the Dev Drive.
#
# rustc and clippy-driver commit several times the memory they touch, and a windows-2025 runner's commit limit is its
# 16 GB of memory plus a 3 GB page file. Without more page file, Bazel jobs failed with "The paging file is too small"
# (os error 1455), "memory allocation of N bytes failed" and "failed to mmap file" for rlibs and the sysroot.
#
# Windows allocates the whole minimum at once. On D: it takes space from the Dev Drive, whose dynamic VHD also lives
# there: a 94 GB and then a 78 GB Dev Drive filled with os error 112 behind a 32 GB page file on D:. C: had about 29 GB
# free, too little for the whole page file, so C: takes as much of the minimum as leaves it 12 GB for the toolchains and
# Cargo registry installed there later, and D: takes the rest and the 16 GB of growth. Run this before
# size-windows-dev-drive.ps1, which sizes the Dev Drive from the space that this leaves on D:.
#
# add-windows-page-file.ps1 must be in the same directory as this script.

$ErrorActionPreference = 'Stop'

$minimumMiB = 32768
$growthMiB = 16384
$systemHeadroomMiB = 12288
$addPageFile = Join-Path $PSScriptRoot 'add-windows-page-file.ps1'
$systemFreeMiB = [long][math]::Floor((Get-PSDrive -Name C).Free / 1MB)
$hasSecondary = $null -ne (Get-PSDrive -Name D -ErrorAction SilentlyContinue)
$systemMiB = if ($hasSecondary) {
  [long][math]::Max(0, [math]::Min($minimumMiB, $systemFreeMiB - $systemHeadroomMiB))
} else { $minimumMiB }
$secondaryMiB = $minimumMiB - $systemMiB
# The growth goes on C: only when C: holds the whole minimum, and then only into C:'s space beyond the headroom.
$systemMaximumMiB = if ($secondaryMiB -gt 0) { $systemMiB } elseif ($hasSecondary) {
  [long][math]::Min($minimumMiB + $growthMiB, $systemFreeMiB - $systemHeadroomMiB)
} else { $minimumMiB + $growthMiB }
Write-Output ("C: has {0} GB free; the page file puts {1} MiB on C: and {2} MiB on D:." -f `
  [math]::Floor($systemFreeMiB / 1024), $systemMiB, $secondaryMiB)
if ($systemMiB -gt 0) {
  & $addPageFile -Path 'C:\pagefile-bazel.sys' -MinimumMiB $systemMiB -MaximumMiB $systemMaximumMiB
}
if ($secondaryMiB -gt 0) {
  & $addPageFile -Path 'D:\pagefile-bazel.sys' -MinimumMiB $secondaryMiB -MaximumMiB ($secondaryMiB + $growthMiB)
}
