# Builds the distributable "green" (portable) package for Litematic Render.
#
#   pwsh -File package.ps1
#
# Output: dist/LitematicRender/  — zip it and hand it to anyone; no Java, no Node, no .NET needed.
#
# The extracted layout is deliberately flat, with the launcher at the root:
#
#   投影预览.exe     <- double-click this
#   bin/                    compiled Java render engine
#   runtime/                jlink-trimmed Java runtime, including HTTPS crypto
#   assets/vanilla/         bundled Minecraft resources
#   assets/builtin/         built-in resource packs
#   webapp/                 Electron shell + UI
#   renders/                default export folder
#
# Everything this script needs is inside the project or the workspace: a JDK to compile and to
# build the trimmed runtime, the Electron payload under webapp/, and the Windows C# compiler for
# the launcher. Nothing is downloaded.

param(
    [string]$JdkHome = "",
    [string]$ProjectRoot = $PSScriptRoot,
    [switch]$SkipElectron,
    [switch]$ReuseRuntime,
    # Package a fresh launcher even when the source checkout's EXE is open elsewhere.
    [switch]$SkipRootLauncherCopy,
    [ValidatePattern('^[\p{L}\p{N}_-]+$')]
    [string]$PackageName = '投影预览',
    [ValidatePattern('^\d+\.\d+\.\d+(\.\d+)?$')]
    [string]$Version = '1.0.17.10'
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = [IO.Path]::GetFullPath($ProjectRoot)
Set-Location $ProjectRoot

function Remove-BuildDirectory {
    param([string]$Path)
    $target = [IO.Path]::GetFullPath($Path)
    if (-not $target.StartsWith($ProjectRoot.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to remove a path outside the project: $target"
    }
    if (Test-Path -LiteralPath $target) { Remove-Item -LiteralPath $target -Recurse -Force }
}

$distRoot  = Join-Path $ProjectRoot 'dist'
$stageDir  = Join-Path $distRoot $PackageName
$webapp    = Join-Path $ProjectRoot 'webapp'

# Finds a JDK that can both compile and build the trimmed runtime.
#
# Newer linkable JDK images can run jlink from lib/modules without a separate jmods directory.
# Both the compiler and linker are required unless an existing runtime is explicitly reused.
function Find-Jdk {
    param([string]$Explicit)
    # The parameter must not be called $home: PowerShell's $HOME is read-only, and a parameter of
    # that name fails at binding time with "Cannot overwrite variable home".
    $usable = {
        param($jdkDir)
        $jdkDir -and
        (Test-Path (Join-Path $jdkDir 'bin/javac.exe')) -and
        ($ReuseRuntime -or ((Test-Path (Join-Path $jdkDir 'bin/jlink.exe')) -and
          ((Test-Path (Join-Path $jdkDir 'jmods')) -or (Test-Path (Join-Path $jdkDir 'lib/modules')))))
    }
    if (& $usable $Explicit) { return $Explicit }
    # A bundled JDK in the workspace is this project's own toolchain and wins over the ambient
    # JAVA_HOME, which on a machine with the Minecraft launcher points at a JRE.
    $beside = Get-ChildItem (Join-Path $ProjectRoot '..\..') -Directory -Filter 'jdk*' -ErrorAction SilentlyContinue |
              Sort-Object Name -Descending
    foreach ($d in $beside) {
        if (& $usable $d.FullName) { return $d.FullName }
    }
    if (& $usable $env:JAVA_HOME) { return $env:JAVA_HOME }
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin/javac.exe'))) {
        throw "JAVA_HOME ($env:JAVA_HOME) cannot build a runtime. Pass -JdkHome <linkable JDK>."
    }
    throw "No linkable JDK found. Pass -JdkHome <path to a full JDK>."
}

$jdk   = Find-Jdk -Explicit $JdkHome
$javac = Join-Path $jdk 'bin/javac.exe'
$jlink = Join-Path $jdk 'bin/jlink.exe'
Write-Host "JDK: $jdk"

# ---------------------------------------------------------------- 1. compile the engine
Write-Host "`n[1/7] Compiling the render engine..."
$binDir = Join-Path $ProjectRoot 'bin'
Remove-BuildDirectory $binDir
New-Item -ItemType Directory -Path $binDir | Out-Null
$sources = Get-ChildItem (Join-Path $ProjectRoot 'src') -Recurse -Filter *.java | ForEach-Object { $_.FullName }
Write-Host "      $($sources.Count) source files"
& $javac --release 21 -encoding UTF-8 -nowarn -d $binDir $sources
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

# --------------------------------------------------------------------------- 2. launcher exe
Write-Host "`n[2/7] Building the root launcher (投影预览.exe)..."
$launcherDir = Join-Path $ProjectRoot 'tools\launcher'
$launcherExe = Join-Path $launcherDir 'bin\投影预览.exe'
# Run it in this same shell: the build script is plain PowerShell and needs no child host,
# which also keeps it working on Windows PowerShell 5.1 where `pwsh` may not exist.
& (Join-Path $launcherDir 'build-launcher.ps1') -OutDir (Join-Path $launcherDir 'bin') | Out-Host
if (-not (Test-Path $launcherExe)) { throw "launcher build produced no exe" }
if (-not $SkipRootLauncherCopy) {
    Copy-Item -LiteralPath $launcherExe -Destination (Join-Path $ProjectRoot '投影预览.exe') -Force
}

# ----------------------------------------------------------------------------- 3. java runtime
Write-Host "`n[3/7] Building the trimmed Java runtime (jlink)..."
$bundledRuntime = Join-Path $ProjectRoot 'runtime'
if ($ReuseRuntime) {
    $runtimeJava = Join-Path $bundledRuntime 'bin/java.exe'
    if (-not (Test-Path -LiteralPath $runtimeJava)) { throw 'No bundled runtime to reuse' }
    $modules = & $runtimeJava --list-modules
    $runtimeExit = $LASTEXITCODE
    $baseModule = $modules | Where-Object { $_ -match '^java.base@' } | Select-Object -First 1
    $runtimeMajor = if ($baseModule) { [int]($baseModule -replace '^java.base@([0-9]+).*$', '$1') } else { 0 }
    if ($runtimeExit -ne 0 -or $runtimeMajor -lt 21 -or -not ($modules -match '^java.desktop@') -or
        -not ($modules -match '^jdk.crypto.ec@')) {
        throw 'Bundled runtime must provide Java 21 or newer, with java.desktop and jdk.crypto.ec for HTTPS skins'
    }
    Write-Host "      Reusing validated bundled Java $runtimeMajor runtime"
} else {
    Remove-BuildDirectory $bundledRuntime
    & $jlink --add-modules java.base,java.desktop,jdk.crypto.ec --strip-debug --no-header-files --no-man-pages `
             --compress=zip-6 --output $bundledRuntime
    if ($LASTEXITCODE -ne 0) { throw "jlink failed" }
}

# ----------------------------------------------------------------------------------- 4. stage
Write-Host "`n[4/7] Staging the package..."
Remove-BuildDirectory $stageDir
New-Item -ItemType Directory -Path $stageDir | Out-Null

foreach ($item in @('bin', 'runtime', 'assets')) {
    $src = Join-Path $ProjectRoot $item
    if (-not (Test-Path $src)) { throw "missing $item" }
    Copy-Item $src -Destination $stageDir -Recurse
}
New-Item -ItemType Directory -Path (Join-Path $stageDir 'renders') -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $stageDir 'logs') -Force | Out-Null
# The launcher sits at the root, which is the whole point of the package layout.
Copy-Item $launcherExe -Destination $stageDir
Copy-Item -LiteralPath (Join-Path $ProjectRoot 'README.md') -Destination $stageDir
$evaluationDocs = Join-Path $ProjectRoot '评估与测评'
if (Test-Path -LiteralPath $evaluationDocs) {
    Copy-Item -LiteralPath $evaluationDocs -Destination $stageDir -Recurse
}
# Public texture/profile caches allow previously viewed heads to render offline.
# Local PNG overrides and the user's networking preference remain personal to their install.
foreach ($part in @('textures', 'profiles')) {
    $cached = Join-Path $ProjectRoot ('skins/' + $part)
    if (Test-Path -LiteralPath $cached) {
        $stageSkins = Join-Path $stageDir 'skins'
        New-Item -ItemType Directory -Path $stageSkins -Force | Out-Null
        Copy-Item -LiteralPath $cached -Destination $stageSkins -Recurse
    }
}

# ---------------------------------------------------------------------------------- 5. webapp
Write-Host "`n[5/7] Copying the Electron shell..."
$stageWebapp = Join-Path $stageDir 'webapp'
New-Item -ItemType Directory -Path $stageWebapp | Out-Null
foreach ($f in @('main.js', 'preload.js', 'engine-client.js', 'runtime.js', 'ui-settings.js', 'scene-transfer.js', 'cache-cleanup.js', 'package.json', 'app.ico', 'icon.png')) {
    Copy-Item (Join-Path $webapp $f) -Destination $stageWebapp
}
Copy-Item (Join-Path $webapp 'renderer') -Destination $stageWebapp -Recurse

if (-not $SkipElectron) {
    $electronSrc = Join-Path $webapp 'node_modules/electron'
    if (-not (Test-Path $electronSrc)) { throw "electron not installed; run install.bat (needs network once)" }

    # The shell only needs the Electron binary tree plus the electron module's metadata.
    $nmElectron = Join-Path $stageWebapp 'node_modules/electron'
    New-Item -ItemType Directory -Path $nmElectron -Force | Out-Null
    Copy-Item (Join-Path $electronSrc 'package.json') -Destination $nmElectron
    Copy-Item (Join-Path $electronSrc 'dist') -Destination $nmElectron -Recurse
}

# ----------------------------------------------------------------------------------- 6. prune
Write-Host "`n[6/7] Pruning non-Windows and unused payloads..."
if (-not $SkipElectron) {
    $dist = Join-Path $stageWebapp 'node_modules/electron/dist'
    # Electron ships every locale and several platform payloads; keep the ones we need.
    $locales = Join-Path $dist 'locales'
    if (Test-Path $locales) {
        Get-ChildItem $locales -Filter '*.pak' |
            Where-Object { $_.Name -notmatch '^(en-US|zh-CN|zh-TW)\.pak$' } |
            Remove-Item -Force
    }
}
# Engine sources, project scripts and diagnostic artifacts never ship.
foreach ($junk in @('src', 'tools', 'packs', 'archive', '.dsh-acl-reports')) {
    $p = Join-Path $stageDir $junk
    Remove-BuildDirectory $p
}

# ---------------------------------------------------------------------------------- 7. report
Write-Host "`n[7/7] Package summary"
$files = Get-ChildItem $stageDir -Recurse -File
$total = ($files | Measure-Object Length -Sum).Sum
Write-Host ("      {0} files, {1:N1} MB" -f $files.Count, ($total / 1MB))
foreach ($part in @('bin', 'runtime', 'assets', 'webapp')) {
    $p = Join-Path $stageDir $part
    if (Test-Path $p) {
        $s = (Get-ChildItem $p -Recurse -File | Measure-Object Length -Sum).Sum
        Write-Host ("        {0,-8} {1,8:N1} MB" -f $part, ($s / 1MB))
    }
}
$exeInfo = Get-Item (Join-Path $stageDir '投影预览.exe')
Write-Host ("        {0,-8} {1,8:N1} MB  (sha256 {2})" -f 'exe', ($exeInfo.Length / 1MB),
    (Get-FileHash $exeInfo.FullName -Algorithm SHA256).Hash)

Write-Host "`nReady: $stageDir"
if (-not $SkipElectron) {
    $zipPath = Join-Path $distRoot ($PackageName + '-' + $Version + '.zip')
    if (Test-Path -LiteralPath $zipPath) { Remove-Item -LiteralPath $zipPath -Force }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    # No wrapper folder: the EXE is an entry at the ZIP's root.
    [IO.Compression.ZipFile]::CreateFromDirectory($stageDir, $zipPath,
        [IO.Compression.CompressionLevel]::Optimal, $false)
    Write-Host "ZIP: $zipPath"
    Write-Host ("SHA256: {0}" -f (Get-FileHash -LiteralPath $zipPath -Algorithm SHA256).Hash)
}
