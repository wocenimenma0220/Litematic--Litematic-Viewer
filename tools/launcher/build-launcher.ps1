# Builds 投影预览.exe — the single root launcher of the portable package.
#
#   pwsh -File build-launcher.ps1 [-OutDir bin]
#
# Uses only what ships with Windows: the .NET Framework 4.x C# compiler. No SDK, no NuGet,
# no network access. A 45 KB windowed exe is produced in about a fifth of a second.
#
# Why not something else (all four were built and measured before choosing this):
#   * dotnet publish --self-contained  -> 36 MB, ~56 s, and restore contacts nuget.org;
#   * jpackage --type app-image        -> builds a JAVA launcher driven by app\*.cfg, and drags
#                                         in a second 122 MB runtime next to the one we ship;
#   * iexpress                         -> cannot express the extracted layout;
#   * MSVC / MinGW                     -> not present on a stock Windows install.
#
# The exe is deliberately tiny because everything it launches is already in the package:
# it only resolves paths and starts Electron (or, for --cli, the bundled JVM).

param(
    [string]$OutDir = (Join-Path $PSScriptRoot 'bin'),
    [string]$Icon   = (Join-Path $PSScriptRoot 'app.ico')
)

$ErrorActionPreference = 'Stop'

# The 64-bit compiler is preferred; the 32-bit one is the fallback on unusual installs.
$cscCandidates = @(
    "$env:WINDIR\Microsoft.NET\Framework64\v4.0.30319\csc.exe",
    "$env:WINDIR\Microsoft.NET\Framework\v4.0.30319\csc.exe"
)
$csc = $cscCandidates | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $csc) { throw "No C# compiler found. Looked in: $($cscCandidates -join '; ')" }

$src = Join-Path $PSScriptRoot 'Launcher.cs'
if (-not (Test-Path $src)) { throw "Missing $src" }
if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Path $OutDir -Force | Out-Null }

$exe = Join-Path $OutDir '投影预览.exe'

# /target:winexe  -> GUI subsystem, so double-clicking never flashes a console window
# /platform:x64   -> matches the 64-bit Electron and JVM shipped alongside
# /optimize+      -> smaller and faster
# /win32icon      -> the icon Explorer and the taskbar show (needs a real .ico, not a .png)
$cscArgs = @(
    '/nologo'
    '/target:winexe'
    '/platform:x64'
    '/optimize+'
    '/codepage:65001'
    "/out:$exe"
    $src
)
if (Test-Path $Icon) { $cscArgs = @('/win32icon:' + $Icon) + $cscArgs }
else { Write-Warning "Icon not found at $Icon; building without one." }

Write-Host "compiler: $csc"
& $csc @cscArgs
if ($LASTEXITCODE -ne 0) { throw "csc failed with exit code $LASTEXITCODE" }

$fi = Get-Item $exe
$sha = (Get-FileHash $exe -Algorithm SHA256).Hash
Write-Host ("`nbuilt {0}  ({1:N0} bytes)  sha256={2}" -f $exe, $fi.Length, $sha)
