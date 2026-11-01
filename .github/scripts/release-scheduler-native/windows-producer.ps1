<#
.SYNOPSIS
Produce the authenticated Frida17.24.3 Windows DLL on the matching hosted worker.
.PARAMETER Architecture
Exactly x64 or arm64, supplied by the enclosing native host validator.
.DESCRIPTION
The enclosing command supplies a fresh owned directory, scripts/exports.txt and
scripts/devkit-{x64,arm64}.sha256. Only official hash-verified devkit bytes are used.
The matching VS installation supplies vcvars, cl, link and dumpbin. The static
archive must define every required export; the resulting DLL must export each.
TEMP/TMP point inside this owned directory. Failed inputs/products/logs remain for
the job owner; this setup does not launch compiler workloads or establish native
scheduler acceptance. No native product is published to another caller here.
.OUTPUTS
producer/frida-core.dll, exact export inventories and producer-receipt.json.
Errors terminate the command; successful return means only this producer finished.
#>
param([string]$Architecture)
$ErrorActionPreference='Stop'
Set-Location $PSScriptRoot
$env:TEMP=Join-Path $PSScriptRoot 'tmp'; $env:TMP=$env:TEMP
$vswhere="${env:ProgramFiles(x86)}\Microsoft Visual Studio\Installer\vswhere.exe"
$vs=& $vswhere -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
if (!$vs) {throw 'MSVC installation absent'}
$script=if ($Architecture -eq 'x64') {'vcvars64.bat'} elseif ($env:PROCESSOR_ARCHITECTURE -eq 'ARM64') {'vcvarsarm64.bat'} else {'vcvarsamd64_arm64.bat'}
$vcvars=Join-Path $vs "VC\Auxiliary\Build\$script"
$output=& cmd.exe /c "`"$vcvars`" && set" 2>&1
if ($LASTEXITCODE -ne 0) {throw "vcvars failed $LASTEXITCODE"}
foreach($line in $output) {if ($line -match '^([^=]+)=(.*)$') {[Environment]::SetEnvironmentVariable($matches[1],$matches[2],'Process')}}
$kit=Join-Path $PSScriptRoot 'devkit'; New-Item -ItemType Directory -Force $kit | Out-Null
$producer=Join-Path $PSScriptRoot 'producer'; New-Item -ItemType Directory -Force $producer | Out-Null
$nativeArch=if($Architecture -eq 'x64') {'x86_64'} else {'arm64'}
$url="https://github.com/frida/frida/releases/download/17.24.3/frida-core-devkit-17.24.3-windows-$nativeArch.tar.xz"
$archive=Join-Path $PSScriptRoot 'devkit.tar.xz'
if (!(Test-Path $archive)) {& curl.exe -fL --retry 2 $url -o $archive;if($LASTEXITCODE -ne 0){throw 'Devkit download failed'}}
$hash=(Get-FileHash -Algorithm SHA256 $archive).Hash.ToLowerInvariant()
$expected=Get-Content (Join-Path $PSScriptRoot "scripts/devkit-$Architecture.sha256")
if($hash -ne $expected){throw "Devkit hash mismatch: $hash"}
& "$env:SystemRoot/System32/tar.exe" -xf $archive -C $kit
if($LASTEXITCODE -ne 0){throw 'Native archive extraction failed'}
$lib=Join-Path $kit 'frida-core.lib'
$symbols=& dumpbin.exe /symbols $lib
if($LASTEXITCODE -ne 0){throw 'Static symbol inventory failed'}
$symbols | Set-Content -Encoding UTF8 (Join-Path $producer 'static-symbols.txt')
$required=Get-Content (Join-Path $PSScriptRoot 'scripts/exports.txt')
$defined=@{}
foreach($line in $symbols){
 if($line -notmatch 'UNDEF' -and $line -match 'External.*\|\s+([a-zA-Z_][a-zA-Z0-9_]*)\s*$'){$defined[$matches[1]]=$true}
}
foreach($name in $required){if(!$defined.ContainsKey($name)){throw "Required defined native symbol absent: $name"}}
$def=Join-Path $producer 'frida-core.def'
(@('EXPORTS')+$required) | Set-Content -Encoding ASCII $def
$stub=Join-Path $producer 'stub.c';$obj=Join-Path $producer 'stub.obj';$dll=Join-Path $producer 'frida-core.dll'
'void __frida_java_link_anchor(void) {}' | Set-Content -Encoding ASCII $stub
& cl.exe /nologo /c /O2 "/Fo$obj" $stub
if($LASTEXITCODE -ne 0){throw 'Native stub compile failed'}
$machine=if($Architecture -eq 'x64') {'X64'} else {'ARM64'}
$args=@('/DLL',"/OUT:$dll","/MACHINE:$machine",$obj,$lib,'ws2_32.lib','winmm.lib','psapi.lib','shlwapi.lib','ole32.lib','oleaut32.lib','uuid.lib','setupapi.lib','advapi32.lib','shell32.lib','user32.lib','kernel32.lib','gdi32.lib','dbghelp.lib','version.lib','winhttp.lib','crypt32.lib','wintrust.lib','bcrypt.lib','ncrypt.lib','dnsapi.lib','iphlpapi.lib','wbemuuid.lib','comctl32.lib','/SUBSYSTEM:WINDOWS','/DYNAMICBASE','/NXCOMPAT','/MANIFEST:NO','/INCREMENTAL:NO','/OPT:REF','/OPT:ICF',"/DEF:$def")
& link.exe @args
if($LASTEXITCODE -ne 0){throw 'Native producer link failed'}
$exports=& dumpbin.exe /exports $dll
if($LASTEXITCODE -ne 0){throw 'DLL export inspection failed'}
$exports | Set-Content -Encoding UTF8 (Join-Path $producer 'exports.txt')
foreach($name in $required){if(!($exports | Where-Object {$_ -match ('\s'+[regex]::Escape($name)+'\s*$')})){throw "Required DLL export absent: $name"}}
@{architecture=$Architecture;url=$url;archiveSha256=$hash;librarySha256=(Get-FileHash -Algorithm SHA256 $lib).Hash.ToLowerInvariant();dllSha256=(Get-FileHash -Algorithm SHA256 $dll).Hash.ToLowerInvariant();exportCount=$required.Count} | ConvertTo-Json | Set-Content -Encoding UTF8 producer-receipt.json
