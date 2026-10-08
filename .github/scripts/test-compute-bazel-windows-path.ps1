param([Parameter(Mandatory = $true)][string]$WorkDirectory)

$ErrorActionPreference = 'Stop'
$fixtureDirectory = Join-Path $WorkDirectory ([guid]::NewGuid().ToString())
[void](New-Item -ItemType Directory -Path $fixtureDirectory)
$savedEnvironment = @{}
foreach ($name in @('PATH', 'WINDIR', 'LOCALAPPDATA', 'GITHUB_ENV')) {
  $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name)
}

function Get-Command {
  [CmdletBinding()]
  param([string]$Name)
  $sources = @{
    git = 'C:\Program Files\Git\cmd\git.exe'
    node = 'C:\hostedtoolcache\windows\node\22\x64\node.exe'
    python = 'C:\hostedtoolcache\windows\Python\3.12\x64\python.exe'
    python3 = 'C:\hostedtoolcache\windows\Python\3.12\x64\python.exe'
    pwsh = 'C:\Program Files\PowerShell\7\pwsh.exe'
  }
  if ($sources.ContainsKey($Name)) { return @{ Source = $sources[$Name] } }
}

function Test-Path {
  param([string]$Path)
  return $Path -eq 'C:\mingw64\bin'
}

try {
  $env:WINDIR = 'C:\Windows'
  $env:LOCALAPPDATA = 'C:\Users\runneradmin\AppData\Local'
  $results = @{}
  foreach ($target in @('x86_64-pc-windows-msvc', 'x86_64-pc-windows-gnullvm')) {
    foreach ($runtime in @('ucrt64', 'mingw64')) {
      $env:PATH = "C:\Program Files\Git\cmd;C:\Program Files\Git\usr\bin;C:\Program Files\Git\$runtime\bin;C:\mingw64\bin;C:\Windows\System32"
      $env:GITHUB_ENV = Join-Path $fixtureDirectory "$target-$runtime.env"
      & "$PSScriptRoot/compute-bazel-windows-path.ps1" -Target $target
      $result = [IO.File]::ReadAllLines($env:GITHUB_ENV) | Where-Object { $_.StartsWith('CODEX_BAZEL_WINDOWS_PATH=') }
      if (@($result).Count -ne 1) { throw 'Expected one PATH export.' }
      $results["$target-$runtime"] = $result
      foreach ($required in @('C:\Program Files\Git\cmd', 'C:\Program Files\Git\usr\bin', 'C:\mingw64\bin', 'C:\Windows\System32')) {
        if (-not $result.Contains($required)) { throw "Missing required tool or DLL path: $required" }
      }
      if ($target.EndsWith('-gnullvm') -and -not $result.Contains("Git\$runtime\bin")) {
        throw 'GNU tests must retain their Git runtime DLL directory.'
      }
    }
  }
  if ($results['x86_64-pc-windows-msvc-ucrt64'] -ne $results['x86_64-pc-windows-msvc-mingw64']) {
    throw 'MSVC action PATH changes with the runner Git runtime variant.'
  }
  Write-Host 'Windows Bazel PATH regression checks passed.'
} finally {
  foreach ($name in $savedEnvironment.Keys) {
    [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name])
  }
  Remove-Item -LiteralPath $fixtureDirectory -Recurse -Force
}
