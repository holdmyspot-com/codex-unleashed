<#
.SYNOPSIS
Exercises Dev Drive BFS setup outcomes with controlled PowerShell command functions.
.DESCRIPTION
Runs the actual top-level setup script once per case under controlled command functions.
Command functions model documented command exit codes and outputs; they capture whether CI_BUILD_ROOT is written.
#>
[CmdletBinding()]
param()

# The controlled command functions read these scripted outcomes and record the operations and CI environment writes.
$script:TestState = $null

<#
.SYNOPSIS
Creates controlled command outcomes for one Dev Drive setup case.
.PARAMETER Overrides
Optional case-specific responses or failure switches.
#>
function Reset-TestState {
    param([hashtable]$Overrides = @{})

    $script:TestState = [ordered]@{
        ExistingDrivePresent = $false
        ExistingFileSystem = "NTFS"
        ExistingVolumeFlags = [uint32]0
        ExistingFileSystemFails = $false
        ExistingVolumeFlagsFail = $false
        NewFileSystem = "ReFS"
        NewVolumeFlags = [uint32]0x00002000
        NewVolumeFlagsFail = $false
        AddTypeEmitsMultipleTypes = $false
        ServiceExit = 1060
        AllowlistExit = 0
        FlTMcExit = 0
        FlTMcOutput = @("  BFS 0 1")
        OriginalUniqueId = "volume-original"
        RemountedUniqueId = "volume-original"
        FailDismount = $false
        FailRemount = $false
        MountCount = 0
        Calls = [System.Collections.Generic.List[string]]::new()
        EnvironmentLines = [System.Collections.Generic.List[string]]::new()
    }

    foreach ($key in $Overrides.Keys) {
        $script:TestState[$key] = $Overrides[$key]
    }

    Set-TestVolumeReaders
    $global:LASTEXITCODE = 0
}

<#
.SYNOPSIS
Confirms that a controlled setup result matches an expected value.
.PARAMETER Expected
The required outcome.
.PARAMETER Actual
The observed outcome.
.PARAMETER Description
The result being checked.
#>
function Assert-Equal {
    param($Expected, $Actual, [string]$Description)

    if ($Expected -is [array]) {
        $expectedText = $Expected -join "`n"
        $actualText = @($Actual) -join "`n"
        if ($expectedText -ceq $actualText) {
            return
        }
        throw "$Description expected '$expectedText' but received '$actualText'."
    }

    if ($Expected -ne $Actual) {
        throw "$Description expected '$Expected' but received '$Actual'."
    }
}

<#
.SYNOPSIS
Runs Dev Drive setup and checks that failures do not publish a build root.
.PARAMETER Name
The case name shown in assertion failures.
.PARAMETER Overrides
Controlled command results for this case.
.PARAMETER ShouldFail
Whether setup must throw.
.PARAMETER ExpectedLine
The exact CI_BUILD_ROOT line expected after success.
#>
function Invoke-SetupCase {
    param(
        [string]$Name,
        [hashtable]$Overrides = @{},
        [bool]$ShouldFail,
        [string]$ExpectedLine
    )

    Reset-TestState $Overrides
    $failure = $null
    try {
        . $script:SetupScriptPath | Out-Null
    } catch {
        $failure = $_
    }

    if ($ShouldFail) {
        if ($null -eq $failure) {
            throw "$Name expected setup to fail."
        }
    } elseif ($null -ne $failure) {
        throw "$Name unexpectedly failed: $failure"
    }
    if ($ShouldFail) {
        Assert-Equal 0 $script:TestState.EnvironmentLines.Count "$Name CI_BUILD_ROOT writes"
    } else {
        Assert-Equal @($ExpectedLine) @($script:TestState.EnvironmentLines) "$Name CI_BUILD_ROOT line"
        Assert-Equal "/runner/github-env" $script:TestState.EnvironmentPath "$Name environment file path"
    }

}

# The test-owned type models platform observations while setup retains the real bit decision.
Microsoft.PowerShell.Utility\Add-Type -TypeDefinition @'
using System;

namespace Codex
{
    public static class DevDriveVolumeStateHelper
    {
    }

    public static class DevDriveVolumeState
    {
        public static Func<string, string> FileSystemReader;
        public static Func<string, uint> VolumeFlagsReader;

        public static string QueryFileSystem(string drive)
        {
            return FileSystemReader(drive);
        }

        public static uint QueryVolumeFlags(string drive)
        {
            return VolumeFlagsReader(drive);
        }
    }
}
'@ -PassThru -ErrorAction Stop | Out-Null

# Add-Type returns the test-owned type to production for same-process controlled observations.
function Add-Type {
    param(
        [string]$TypeDefinition,
        [switch]$PassThru,
        [System.Management.Automation.ActionPreference]$ErrorAction
    )
    $script:TestState.Calls.Add("Add-Type")
    if ($script:TestState.AddTypeEmitsMultipleTypes) {
        return [System.Type[]]@([Codex.DevDriveVolumeStateHelper], [Codex.DevDriveVolumeState])
    }
    return [Codex.DevDriveVolumeState]
}

function Set-TestVolumeReaders {
    [Codex.DevDriveVolumeState]::FileSystemReader = [System.Func[string, string]] {
        param([string]$Drive)
        $script:TestState.Calls.Add("QueryFileSystem $Drive")
        if ($Drive -eq "D:" -and $script:TestState.ExistingFileSystemFails) {
            throw [System.ComponentModel.Win32Exception]::new(5)
        }
        if ($Drive -eq "D:") {
            return [string]$script:TestState.ExistingFileSystem
        }
        return [string]$script:TestState.NewFileSystem
    }
    [Codex.DevDriveVolumeState]::VolumeFlagsReader = [System.Func[string, uint32]] {
        param([string]$Drive)
        $script:TestState.Calls.Add("QueryVolumeFlags $Drive")
        if ($Drive -eq "D:" -and $script:TestState.ExistingVolumeFlagsFail) {
            throw [System.ComponentModel.Win32Exception]::new(5)
        }
        if ($Drive -eq "D:") {
            return [uint32]$script:TestState.ExistingVolumeFlags
        }
        if ($script:TestState.NewVolumeFlagsFail) {
            throw [System.ComponentModel.Win32Exception]::new(5)
        }
        return [uint32]$script:TestState.NewVolumeFlags
    }
}

# Test-Path models whether the runner exposes an existing volume at D:.
function Test-Path {
    param([string]$Path)
    $script:TestState.Calls.Add("Test-Path $Path")
    $isExistingDrive = $Path -eq "D:\" -and $script:TestState.ExistingDrivePresent
    return $isExistingDrive
}

# fsutil models Dev Drive verification, trust setup, and filter allowlisting with explicit exit statuses.
function fsutil {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
    $script:TestState.Calls.Add("fsutil $($Arguments -join ' ')")
    $isDevDriveQuery = $Arguments[0] -eq "devdrv" -and $Arguments[1] -eq "query"
    if ($isDevDriveQuery) {
        $global:LASTEXITCODE = 0
        Write-Output "This is not a developer volume."
    } elseif ($Arguments[1] -eq "setFiltersAllowed") {
        $global:LASTEXITCODE = $script:TestState.AllowlistExit
    } else {
        $global:LASTEXITCODE = 0
    }
}

# sc.exe models the Windows Service Control Manager query for the BFS driver.
function sc.exe {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
    $script:TestState.Calls.Add("sc.exe $($Arguments -join ' ')")
    $global:LASTEXITCODE = $script:TestState.ServiceExit
}

# fltmc.exe models the Windows filter-manager listing and its reported filter names.
function fltmc.exe {
    param([string]$Action, [switch]$V, [string]$Drive)
    $script:TestState.Calls.Add("fltmc.exe $Action -v $Drive")
    $global:LASTEXITCODE = $script:TestState.FlTMcExit
    $script:TestState.FlTMcOutput
}

# New-VHD models creation of the runner-owned VHD file.
function New-VHD {
    param([string]$Path, [long]$SizeBytes, [switch]$Dynamic)
    $script:TestState.Calls.Add("New-VHD $Path")
}

# Mount-VHD models the initial and verification remounts of that owned VHD.
function Mount-VHD {
    param([string]$Path, [switch]$Passthru)
    $script:TestState.MountCount++
    $script:TestState.Calls.Add("Mount-VHD $Path $($script:TestState.MountCount)")
    $isVerificationRemount = $script:TestState.MountCount -gt 1
    $verificationRemountShouldFail = $isVerificationRemount -and $script:TestState.FailRemount
    if ($verificationRemountShouldFail) {
        throw "controlled remount failure"
    }
    if ($Passthru) {
        return [pscustomobject]@{ DiskNumber = 1 }
    }
}

# Dismount-VHD models the VHD lifecycle transition before its verification remount.
function Dismount-VHD {
    param([string]$Path)
    $script:TestState.Calls.Add("Dismount-VHD $Path")
    if ($script:TestState.FailDismount) {
        throw "controlled dismount failure"
    }
}

# Get-Disk models resolving the mounted VHD to its Windows disk.
function Get-Disk {
    process { [pscustomobject]@{ DiskNumber = 1 } }
}

# Initialize-Disk models initializing the newly created VHD's disk.
function Initialize-Disk {
    param([Parameter(ValueFromPipeline = $true)]$InputObject, [string]$PartitionStyle)
    process { }
}

# New-Partition models creating the partition consumed by volume setup.
function New-Partition {
    param([Parameter(ValueFromPipeline = $true)]$InputObject, [switch]$AssignDriveLetter, [switch]$UseMaximumSize)
    process { [pscustomobject]@{ PartitionNumber = 1 } }
}

# Format-Volume models formatting the new partition as the provisioned Dev Drive.
function Format-Volume {
    [CmdletBinding(SupportsShouldProcess = $true)]
    param(
        [Parameter(ValueFromPipeline = $true)]$InputObject,
        [string]$FileSystem,
        [string]$NewFileSystemLabel,
        [switch]$DevDrive,
        [switch]$Force
    )
    process {
        [pscustomobject]@{
            DriveLetter = "R"
            UniqueId = $script:TestState.OriginalUniqueId
        }
    }
}

# Get-Volume models rereading the remounted volume's identity.
function Get-Volume {
    param([string]$DriveLetter)
    $script:TestState.Calls.Add("Get-Volume $DriveLetter")
    [pscustomobject]@{ DriveLetter = $DriveLetter; UniqueId = $script:TestState.RemountedUniqueId }
}

# Out-File models the GitHub Actions environment-file handoff consumed by the next workflow step.
function Out-File {
    param([string]$FilePath, [string]$Encoding, [switch]$Append)
    process {
        $script:TestState.EnvironmentPath = $FilePath
        $script:TestState.EnvironmentLines.Add([string]$_)
    }
}

# Give the production script harmless paths and initialize its command doubles before
# evaluating it, because the production file performs setup at top level.
$env:RUNNER_TEMP = "/runner/temp"
$env:GITHUB_ENV = "/runner/github-env"
$script:ExpectedVhdPath = Join-Path "/runner/temp" "codex-dev-drive.vhdx"
Reset-TestState
$script:SetupScriptPath = Join-Path $PSScriptRoot "setup-dev-drive.ps1"

Invoke-SetupCase -Name "existing ordinary D: provisions owned VHD" -Overrides @{ ExistingDrivePresent = $true; ExistingVolumeFlags = [uint32]0 } -ExpectedLine "CI_BUILD_ROOT=R:"
Assert-Equal $true ($script:TestState.Calls -match "New-VHD") "existing ordinary D: provisions owned VHD"
Assert-Equal $false ($script:TestState.Calls -match "fsutil devdrv trust D:|setFiltersAllowed /volume D:") "existing ordinary D: mutations"
Assert-Equal $false ($script:TestState.Calls -match "QueryVolumeFlags D:") "existing NTFS D: skips state query"

Invoke-SetupCase -Name "existing ReFS D: with clear Dev Volume bit provisions owned VHD" -Overrides @{ ExistingDrivePresent = $true; ExistingFileSystem = "ReFS"; ExistingVolumeFlags = [uint32]0 } -ExpectedLine "CI_BUILD_ROOT=R:"
Assert-Equal $true ($script:TestState.Calls -match "New-VHD") "existing ReFS D: with clear Dev Volume bit provisions owned VHD"

Invoke-SetupCase -Name "existing ReFS D: state query failure stops before provisioning" -Overrides @{ ExistingDrivePresent = $true; ExistingFileSystem = "ReFS"; ExistingVolumeFlagsFail = $true } -ShouldFail $true
Assert-Equal $false ($script:TestState.Calls -match "New-VHD") "existing ReFS D: state query failure provisioning"

Invoke-SetupCase -Name "existing D: filesystem query failure stops before provisioning" -Overrides @{ ExistingDrivePresent = $true; ExistingFileSystemFails = $true } -ShouldFail $true
Assert-Equal $false ($script:TestState.Calls -match "New-VHD") "existing D: filesystem query failure provisioning"

Invoke-SetupCase -Name "new volume with clear Dev Volume bit fails before publication" -Overrides @{ NewVolumeFlags = [uint32]0 } -ShouldFail $true
Assert-Equal $false ($script:TestState.Calls -match "sc.exe query bfs|fsutil devdrv setFiltersAllowed|Dismount-VHD|fltmc.exe") "new volume without Dev Volume skips BFS"

Invoke-SetupCase -Name "new volume state query failure stops before publication" -Overrides @{ NewVolumeFlagsFail = $true } -ShouldFail $true
Assert-Equal $false ($script:TestState.Calls -match "sc.exe query bfs|fsutil devdrv setFiltersAllowed|Dismount-VHD|fltmc.exe") "new volume state query failure skips BFS"

Invoke-SetupCase -Name "existing Dev Drive state 0x2000 bypasses owned-VHD provisioning" -Overrides @{ ExistingDrivePresent = $true; ExistingFileSystem = "ReFS"; ExistingVolumeFlags = [uint32]0x00002000 } -ExpectedLine "CI_BUILD_ROOT=D:"
Assert-Equal $false ($script:TestState.Calls -match "New-VHD|sc.exe|setFiltersAllowed|Dismount-VHD|fltmc.exe") "existing Dev Drive state 0x2000 operations"

Invoke-SetupCase -Name "existing trusted Dev Drive state 0x6000 bypasses owned-VHD provisioning" -Overrides @{ ExistingDrivePresent = $true; ExistingFileSystem = "ReFS"; ExistingVolumeFlags = [uint32]0x00006000 } -ExpectedLine "CI_BUILD_ROOT=D:"
Assert-Equal $false ($script:TestState.Calls -match "New-VHD|sc.exe|setFiltersAllowed|Dismount-VHD|fltmc.exe") "existing trusted Dev Drive state 0x6000 operations"

Invoke-SetupCase -Name "multiple Add-Type outputs select Dev Drive state Type by full name" -Overrides @{ ExistingDrivePresent = $true; ExistingFileSystem = "ReFS"; ExistingVolumeFlags = [uint32]0x00002000; AddTypeEmitsMultipleTypes = $true } -ExpectedLine "CI_BUILD_ROOT=D:"
Assert-Equal $false ($script:TestState.Calls -match "New-VHD|sc.exe|setFiltersAllowed|Dismount-VHD|fltmc.exe") "multiple Add-Type outputs existing Dev Drive operations"

Invoke-SetupCase -Name "service absent leaves provisioning behavior unchanged" -Overrides @{ ServiceExit = 1060 } -ExpectedLine "CI_BUILD_ROOT=R:"
Assert-Equal $false ($script:TestState.Calls -match "fsutil devdrv setFiltersAllowed") "service absent BFS allowlist call"
Assert-Equal $false ($script:TestState.Calls -match "Dismount-VHD") "service absent remount call"
Assert-Equal $false ($script:TestState.Calls -match "fltmc.exe") "service absent attachment query"

Invoke-SetupCase -Name "installed BFS is allowed, remounted, identified and attached" -Overrides @{ ServiceExit = 0 } -ExpectedLine "CI_BUILD_ROOT=R:"
$allowlistIndex = $script:TestState.Calls.IndexOf("fsutil devdrv setFiltersAllowed /volume R: bfs")
$dismountIndex = $script:TestState.Calls.IndexOf("Dismount-VHD $script:ExpectedVhdPath")
$remountIndex = $script:TestState.Calls.IndexOf("Mount-VHD $script:ExpectedVhdPath 2")
$volumeIndex = $script:TestState.Calls.IndexOf("Get-Volume R")
$filterIndex = $script:TestState.Calls.IndexOf("fltmc.exe instances -v R:")
 $allowedBeforeDismount = $allowlistIndex -ge 0 -and $dismountIndex -gt $allowlistIndex
 $dismountBeforeRemount = $remountIndex -gt $dismountIndex
 $remountBeforeIdentityRead = $volumeIndex -gt $remountIndex
 $identityBeforeFilterQuery = $filterIndex -gt $volumeIndex
 $installedBfsStepsAreOrdered = $allowedBeforeDismount -and $dismountBeforeRemount -and $remountBeforeIdentityRead -and $identityBeforeFilterQuery
if ($installedBfsStepsAreOrdered) {
    Write-Output "Installed BFS checks ran in order."
} else {
    throw "Installed BFS setup did not complete checks in order; indexes=$allowlistIndex,$dismountIndex,$remountIndex,$volumeIndex,$filterIndex; calls=$($script:TestState.Calls -join '; ')."
}

Invoke-SetupCase -Name "unexpected service query status fails before build-root publication" -Overrides @{ ServiceExit = 5 } -ShouldFail $true
Invoke-SetupCase -Name "allowlist failure stops before remount and build-root publication" -Overrides @{ ServiceExit = 0; AllowlistExit = 1 } -ShouldFail $true
Assert-Equal $false ($script:TestState.Calls -match "Dismount-VHD") "failed allowlist remount call"
Invoke-SetupCase -Name "dismount failure stops before build-root publication" -Overrides @{ ServiceExit = 0; FailDismount = $true } -ShouldFail $true
Invoke-SetupCase -Name "remount failure stops before build-root publication" -Overrides @{ ServiceExit = 0; FailRemount = $true } -ShouldFail $true
Invoke-SetupCase -Name "empty original volume identity fails before build-root publication" -Overrides @{ ServiceExit = 0; OriginalUniqueId = "" } -ShouldFail $true
Invoke-SetupCase -Name "changed remounted volume identity fails before build-root publication" -Overrides @{ ServiceExit = 0; RemountedUniqueId = "volume-other" } -ShouldFail $true
Invoke-SetupCase -Name "empty remounted volume identity fails before build-root publication" -Overrides @{ ServiceExit = 0; RemountedUniqueId = "" } -ShouldFail $true
Invoke-SetupCase -Name "filter query failure stops before build-root publication" -Overrides @{ ServiceExit = 0; FlTMcExit = 1 } -ShouldFail $true
Invoke-SetupCase -Name "missing exact BFS filter stops before build-root publication" -Overrides @{ ServiceExit = 0; FlTMcOutput = @("bfs-helper 0 1") } -ShouldFail $true

Invoke-SetupCase -Name "existing Dev Drive bypasses BFS mutation" -Overrides @{ ExistingDrivePresent = $true; ExistingFileSystem = "ReFS"; ExistingVolumeFlags = [uint32]0x00002000 } -ExpectedLine "CI_BUILD_ROOT=D:"
Assert-Equal $false ($script:TestState.Calls -match "sc.exe") "existing Dev Drive service query"
Assert-Equal $false ($script:TestState.Calls -match "setFiltersAllowed|Dismount-VHD|fltmc.exe") "existing Dev Drive BFS operations"

Write-Output "All Dev Drive BFS boundary cases passed."
