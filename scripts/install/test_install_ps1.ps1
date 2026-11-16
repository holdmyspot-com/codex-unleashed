param([string]$Installer = (Join-Path $PSScriptRoot 'install.ps1'))

$ErrorActionPreference = 'Stop'
$tokens = $null
$errors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile($Installer, [ref]$tokens, [ref]$errors)
if ($errors.Count -ne 0) { throw "Installer syntax errors: $errors" }
# Load the installer's actual functions without installing into the test host.
foreach ($definition in $ast.FindAll({ param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] }, $false)) {
    Invoke-Expression $definition.Extent.Text
}

Assert-ValidReleaseVersion -Version '0.160.0+39'
$rejected = $false
try { Assert-ValidReleaseVersion -Version '0.160.0+dev' } catch { $rejected = $true }
if (-not $rejected) { throw 'Development versions must be rejected.' }

$script:Release = 'latest'
$script:PreferReleasesOpenAICom = $false
$script:Requests = @()
function Invoke-RestMethod {
    param([string]$Uri)
    $script:Requests += $Uri
    return [PSCustomObject]@{
        tag_name = 'rust-v0.160.0+39'
        assets = @(
            [PSCustomObject]@{ name = 'codex-package-x86_64-pc-windows-msvc.tar.gz'; digest = ('sha256:' + ('a' * 64)); browser_download_url = 'https://github.com/holdmyspot-com/codex-unleashed/releases/download/rust-v0.160.0+39/codex-package-x86_64-pc-windows-msvc.tar.gz' },
            [PSCustomObject]@{ name = 'SHA256SUMS'; digest = ('sha256:' + ('b' * 64)); browser_download_url = 'https://github.com/holdmyspot-com/codex-unleashed/releases/download/rust-v0.160.0+39/SHA256SUMS' }
        )
    }
}
$resolved = Resolve-Release
if ($resolved.Version -cne '0.160.0+39') { throw 'Vendor version is not preserved.' }
if (($script:Requests -join ',') -cne 'https://api.github.com/repos/holdmyspot-com/codex-unleashed/releases/latest') {
    throw "Wrong release source: $script:Requests"
}
$selection = Resolve-ReleaseAssetSelection -ResolvedRelease $resolved -Target 'x86_64-pc-windows-msvc' -NpmTag 'win32-x64'
if ($selection.ChecksumMetadata.Url -cne $resolved.Metadata.assets[1].browser_download_url) { throw "Wrong checksum asset: $($selection.ChecksumMetadata | ConvertTo-Json -Compress)" }
if ($selection.PackageMetadata.Url -cne $resolved.Metadata.assets[0].browser_download_url) { throw 'Wrong package download URL.' }
Write-Output 'PowerShell release selection and version validation passed.'
