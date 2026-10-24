# Configure a fast drive for Windows CI jobs.
#
# GitHub-hosted Windows runners do not always expose a secondary D: volume. When
# they do not, create a Dev Drive VHD. CI depends on this path for its
# build directories where CI spends significant time doing I/O, so fail the
# job if no real Dev Drive is available.

function Test-DevDrive {
    param([string]$Drive)

    & fsutil devdrv query $Drive *> $null
    return $LASTEXITCODE -eq 0
}

function Invoke-BestEffort {
    param([scriptblock]$Script, [string]$Description)

    try {
        & $Script
    } catch {
        Write-Warning "$Description failed: $($_.Exception.Message)"
    }
}

$SupportsDevDrive = (Get-Command Format-Volume -ErrorAction Stop).Parameters.ContainsKey("DevDrive")

if ((Test-Path "D:\") -and (Test-DevDrive "D:")) {
    Write-Output "Using existing Dev Drive at D:"
    $Drive = "D:"
} elseif ((Test-Path "D:\") -and -not $SupportsDevDrive) {
    Write-Output "Using existing regular D: volume on a runner without Dev Drive support."
    $Drive = "D:"
} else {
    if (Test-Path "D:\") {
        Write-Output "Existing D: volume is not a Dev Drive; provisioning a new Dev Drive VHD."
    }

    try {
        $VhdPath = Join-Path $env:RUNNER_TEMP "codex-dev-drive.vhdx"
        $SizeBytes = 64GB

        if (Test-Path $VhdPath) {
            Remove-Item -Path $VhdPath -Force
        }

        New-VHD -Path $VhdPath -SizeBytes $SizeBytes -Dynamic -ErrorAction Stop | Out-Null
        $Mounted = Mount-VHD -Path $VhdPath -Passthru -ErrorAction Stop
        $Disk = $Mounted | Get-Disk -ErrorAction Stop
        $Disk | Initialize-Disk -PartitionStyle GPT -ErrorAction Stop
        $Partition = $Disk | New-Partition -AssignDriveLetter -UseMaximumSize -ErrorAction Stop
        $FormatVolumeArguments = @{
            FileSystem = "ReFS"
            NewFileSystemLabel = "CodexDevDrive"
            Confirm = $false
            Force = $true
            ErrorAction = "Stop"
        }
        if ($SupportsDevDrive) {
            $FormatVolumeArguments.DevDrive = $true
        } else {
            Write-Warning "Format-Volume does not support -DevDrive; using a regular ReFS volume."
        }
        $Volume = $Partition | Format-Volume @FormatVolumeArguments

        $Drive = "$($Volume.DriveLetter):"

        if ($SupportsDevDrive -and -not (Test-DevDrive $Drive)) {
            throw "Provisioned volume at $Drive did not pass Dev Drive verification."
        }

        if ($SupportsDevDrive) {
            Invoke-BestEffort { fsutil devdrv trust $Drive } "Trusting Dev Drive $Drive"
            Invoke-BestEffort { fsutil devdrv enable /disallowAv } "Disabling AV filter attachment for Dev Drives"

            $BfsServiceStatus = @(& sc.exe query bfs 2>&1)
            $BfsServiceExit = $LASTEXITCODE
            $BfsServiceStatus | Write-Output
            if ($BfsServiceExit -ne 0 -and $BfsServiceExit -ne 1060) {
                throw "Cannot query the BFS filesystem driver; sc exited with $BfsServiceExit."
            }
            if ($BfsServiceExit -eq 0) {
                & fsutil devdrv setFiltersAllowed /volume $Drive bfs
                if ($LASTEXITCODE -ne 0) {
                    throw "Failed to allow the BFS filesystem enforcement filter on the new Dev Drive $Drive."
                }

                Dismount-VHD -Path $VhdPath -ErrorAction Stop
                Mount-VHD -Path $VhdPath -ErrorAction Stop
                $RemountedVolume = Get-Volume -DriveLetter $Volume.DriveLetter -ErrorAction Stop
                if ($RemountedVolume.UniqueId -ne $Volume.UniqueId) {
                    throw "The remounted build drive $Drive does not identify the provisioned volume."
                }

                $BfsInstances = @(& fltmc.exe instances -v $Drive 2>&1)
                $BfsQueryExit = $LASTEXITCODE
                $BfsInstances | Write-Output
                if ($BfsQueryExit -ne 0) {
                    throw "Cannot enumerate filesystem filters on the new Dev Drive $Drive; fltmc exited with $BfsQueryExit."
                }
                if (-not ($BfsInstances -match '^\s*bfs\s+')) {
                    throw "BFS is not attached to the new Dev Drive $Drive; MXC filesystem enforcement is unavailable."
                }
            }
        }

        Write-Output "Using Dev Drive at $Drive"
    } catch {
        throw "Failed to create Dev Drive: $($_.Exception.Message)"
    }
}

"CI_BUILD_ROOT=$Drive" | Out-File -FilePath $env:GITHUB_ENV -Encoding utf8 -Append
exit 0
