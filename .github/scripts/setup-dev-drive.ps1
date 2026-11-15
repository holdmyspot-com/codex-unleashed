# Configure a fast drive for Windows CI jobs.
#
# GitHub-hosted Windows runners do not always expose a secondary D: volume. When
# they do not, create a Dev Drive VHD. CI depends on this path for its
# build directories where CI spends significant time doing I/O, so fail the
# job if no real Dev Drive is available.

function Resolve-DevDriveVolumeStateType {
    param([object[]]$EmittedTypes)

    $matchingTypes = @(
        foreach ($emittedType in $EmittedTypes) {
            if (($emittedType -is [System.Type]) -and ($emittedType.FullName -ceq "Codex.DevDriveVolumeState")) {
                $emittedType
            }
        }
    )
    if ($matchingTypes.Count -ne 1) {
        throw "Add-Type must emit exactly one type named 'Codex.DevDriveVolumeState'; found $($matchingTypes.Count)."
    }

    return $matchingTypes[0]
}

$emittedDevDriveTypes = Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.IO;
using System.Runtime.InteropServices;
using Microsoft.Win32.SafeHandles;

namespace Codex
{
    public static class DevDriveVolumeState
    {
        [StructLayout(LayoutKind.Sequential)]
        private struct PersistentVolumeInformation
        {
            public uint VolumeFlags;
            public uint FlagMask;
            public uint Version;
            public uint Reserved;
        }

        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, EntryPoint = "CreateFileW", SetLastError = true)]
        private static extern SafeFileHandle CreateFile(
            string fileName,
            uint desiredAccess,
            uint shareMode,
            IntPtr securityAttributes,
            uint creationDisposition,
            uint flagsAndAttributes,
            IntPtr templateFile);

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern bool DeviceIoControl(
            SafeFileHandle device,
            uint controlCode,
            ref PersistentVolumeInformation input,
            uint inputSize,
            out PersistentVolumeInformation output,
            uint outputSize,
            out uint bytesReturned,
            IntPtr overlapped);

        public static string QueryFileSystem(string drive)
        {
            return new DriveInfo(drive + @"\").DriveFormat;
        }

        public static uint QueryVolumeFlags(string drive)
        {
            using (SafeFileHandle volume = CreateFile(drive + @"\", 0x00000080, 3, IntPtr.Zero, 3, 0x02000000, IntPtr.Zero))
            {
                if (volume.IsInvalid)
                {
                    int nativeError = Marshal.GetLastWin32Error();
                    throw new Win32Exception(nativeError, "Could not open volume " + drive + " (Win32 error " + nativeError + ").");
                }

                PersistentVolumeInformation input = new PersistentVolumeInformation();
                input.FlagMask = 0x00002000;
                input.Version = 1;
                PersistentVolumeInformation output;
                uint bytesReturned;
                bool querySucceeded = DeviceIoControl(volume, 0x0009023c, ref input, 16, out output, 16, out bytesReturned, IntPtr.Zero);
                if (!querySucceeded)
                {
                    int nativeError = Marshal.GetLastWin32Error();
                    throw new Win32Exception(nativeError, "Could not query Dev Drive state for " + drive + " (Win32 error " + nativeError + ").");
                }
                if (bytesReturned < 16)
                {
                    throw new IOException("Dev Drive state query for " + drive + " returned only " + bytesReturned + " bytes.");
                }

                return output.VolumeFlags;
            }
        }
    }
}
'@ -PassThru -ErrorAction Stop
$script:DevDriveVolumeStateType = Resolve-DevDriveVolumeStateType -EmittedTypes $emittedDevDriveTypes

function Test-DevDrive {
    param([string]$Drive)

    # True means the typed state marks a ReFS volume as a Dev Drive; query failures propagate.
    $fileSystem = $script:DevDriveVolumeStateType::QueryFileSystem($Drive)
    $isReFs = [string]::Equals($fileSystem, "ReFS", [System.StringComparison]::OrdinalIgnoreCase)
    if ($isReFs) {
        $volumeFlags = $script:DevDriveVolumeStateType::QueryVolumeFlags($Drive)
        $devVolumeMask = [uint32]0x00002000
        $isDevVolume = ($volumeFlags -band $devVolumeMask) -gt 0
        return $isDevVolume
    }

    return $false
}

function Invoke-BestEffort {
    param([scriptblock]$Script, [string]$Description)

    try {
        & $Script
    } catch {
        Write-Warning "$Description failed: $($_.Exception.Message)"
    }
}

<#
.SYNOPSIS
Creates and verifies a Dev Drive for Windows CI, then records its build root.
.DESCRIPTION
Uses an existing Dev Drive at D: when available. Otherwise creates a Dev Drive VHD in the supplied runner temporary
folder. Appends the selected drive to the supplied GitHub environment file. Provisioning failures throw before that
file is changed.
.PARAMETER RunnerTempPath
Runner-owned temporary directory used for the new Dev Drive VHD.
.PARAMETER GitHubEnvPath
GitHub Actions environment file that receives the selected CI_BUILD_ROOT value.
#>
function Invoke-DevDriveSetup {
    param(
        [string]$RunnerTempPath,
        [string]$GitHubEnvPath
    )

    if ((Test-Path "D:\") -and (Test-DevDrive "D:")) {
        Write-Output "Using existing Dev Drive at D:"
        $Drive = "D:"
    } else {
        if (Test-Path "D:\") {
            Write-Output "Existing D: volume is not a Dev Drive; provisioning a new Dev Drive VHD."
        }

        try {
            $VhdPath = Join-Path $RunnerTempPath "codex-dev-drive.vhdx"
            $SizeBytes = 64GB

            if (Test-Path $VhdPath) {
                Remove-Item -Path $VhdPath -Force
            }

            New-VHD -Path $VhdPath -SizeBytes $SizeBytes -Dynamic -ErrorAction Stop | Out-Null
            $Mounted = Mount-VHD -Path $VhdPath -Passthru -ErrorAction Stop
            $Disk = $Mounted | Get-Disk -ErrorAction Stop
            $Disk | Initialize-Disk -PartitionStyle GPT -ErrorAction Stop
            $Partition = $Disk | New-Partition -AssignDriveLetter -UseMaximumSize -ErrorAction Stop
            $Volume = $Partition | Format-Volume -FileSystem ReFS -NewFileSystemLabel "CodexDevDrive" -DevDrive -Confirm:$false -Force -ErrorAction Stop

            $Drive = "$($Volume.DriveLetter):"

            if (-not (Test-DevDrive $Drive)) {
                throw "Provisioned volume at $Drive did not pass Dev Drive verification."
            }

            Invoke-BestEffort { fsutil devdrv trust $Drive } "Trusting Dev Drive $Drive"
            Invoke-BestEffort { fsutil devdrv enable /disallowAv } "Disabling AV filter attachment for Dev Drives"

            & sc.exe query bfs *> $null
            $BfsServiceExit = $LASTEXITCODE
            $IsBfsInstalled = $BfsServiceExit -eq 0
            $IsBfsAbsent = $BfsServiceExit -eq 1060
            $ServiceStatusIsSupported = $IsBfsInstalled -or $IsBfsAbsent
            if (-not $ServiceStatusIsSupported) {
                throw "Cannot query the BFS filesystem driver; sc.exe exited with $BfsServiceExit."
            }

            if ($IsBfsInstalled) {
                & fsutil devdrv setFiltersAllowed /volume $Drive bfs
                $AllowlistExit = $LASTEXITCODE
                if ($AllowlistExit -ne 0) {
                    throw "Failed to allow the BFS filesystem enforcement filter on the new Dev Drive $Drive."
                }

                Dismount-VHD -Path $VhdPath -ErrorAction Stop
                Mount-VHD -Path $VhdPath -ErrorAction Stop | Out-Null
                $RemountedVolume = Get-Volume -DriveLetter $Volume.DriveLetter -ErrorAction Stop
                $OriginalVolumeHasIdentity = -not [string]::IsNullOrWhiteSpace($Volume.UniqueId)
                if (-not $OriginalVolumeHasIdentity) {
                    throw "The provisioned build drive $Drive has no volume identity."
                }
                if ($RemountedVolume.UniqueId -cne $Volume.UniqueId) {
                    throw "The remounted build drive $Drive does not identify the provisioned volume."
                }

                $BfsInstances = @(& fltmc.exe instances -v $Drive 2>&1)
                $FilterQueryExit = $LASTEXITCODE
                if ($FilterQueryExit -ne 0) {
                    throw "Cannot enumerate filesystem filters on the new Dev Drive $Drive; fltmc.exe exited with $FilterQueryExit."
                }

                $HasBfsFilter = $false
                foreach ($instance in $BfsInstances) {
                    if ([string]$instance -match '^\s*bfs(?:\s|$)') {
                        $HasBfsFilter = $true
                        break
                    }
                }
                if (-not $HasBfsFilter) {
                    throw "BFS is not attached to the new Dev Drive $Drive; MXC filesystem enforcement is unavailable."
                }
            }

            Write-Output "Using Dev Drive at $Drive"
        } catch {
            throw "Failed to create Dev Drive: $($_.Exception.Message)"
        }
    }

    "CI_BUILD_ROOT=$Drive" | Out-File -FilePath $GitHubEnvPath -Encoding utf8 -Append
}

Invoke-DevDriveSetup -RunnerTempPath $env:RUNNER_TEMP -GitHubEnvPath $env:GITHUB_ENV
