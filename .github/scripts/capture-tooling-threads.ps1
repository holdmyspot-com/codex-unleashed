param([Parameter(Mandatory = $true)][string]$OutputDirectory)

$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
[DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ') | Set-Content "$OutputDirectory/started.txt"
foreach ($delay in @(120, 480)) {
    Start-Sleep -Seconds $delay
    $sample = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ')
    Get-CimInstance Win32_Process |
        Where-Object { $_.Name -in @('java.exe', 'rustc.exe', 'cargo.exe', 'git.exe', 'node.exe') } |
        Select-Object Name, ProcessId, ParentProcessId, WorkingSetSize |
        ConvertTo-Json | Set-Content "$OutputDirectory/$sample-processes.json"
    foreach ($process in Get-Process -Name java -ErrorAction SilentlyContinue) {
        $capture = Start-Process -FilePath "$env:JAVA_HOME/bin/jcmd.exe" `
            -ArgumentList @($process.Id, 'Thread.print', '-l') -PassThru -NoNewWindow `
            -RedirectStandardOutput "$OutputDirectory/$sample-$($process.Id).txt" `
            -RedirectStandardError "$OutputDirectory/$sample-$($process.Id).err"
        if (-not $capture.WaitForExit(10000)) {
            $capture.Kill()
            $capture.WaitForExit()
            Add-Content "$OutputDirectory/$sample-$($process.Id).err" 'Thread capture exceeded 10 seconds.'
        }
    }
}
