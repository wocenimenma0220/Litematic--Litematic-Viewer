# Builds the distributable "green" (portable) package for Litematic Render.
#
#   pwsh -File package.ps1
#
# Output: dist/LitematicRender/  (zip it and hand it to anyone; no Java, no Node needed)
#
# The package contains:
#   bin/            compiled Java render engine
#   runtime/        jlink-trimmed Java runtime (~48 MB, java.base + java.desktop)
#   assets/vanilla/ bundled Minecraft resources
#   webapp/         Electron shell + UI
#   *.bat           launchers
#
# Electron's own node_modules are pruned of dev-only and non-Windows payloads.

param(
    [string]$JdkHome = "",
    [string]$ProjectRoot = $PSScriptRoot,
    [switch]$SkipElectron
)

$ErrorActionPreference = 'Stop'
Set-Location $ProjectRoot

$distRoot  = Join-Path $ProjectRoot 'dist'
$stageDir  = Join-Path $distRoot 'LitematicRender'
$webapp    = Join-Path $ProjectRoot 'webapp'

function Find-Jdk {
    param([string]$Explicit)
    if ($Explicit -and (Test-Path (Join-Path $Explicit 'bin/javac.exe'))) { return $Explicit }
    $beside = Get-ChildItem (Join-Path $ProjectRoot '..\..') -Directory -Filter 'jdk*' -ErrorAction SilentlyContinue |
              Sort-Object Name -Descending
    foreach ($d in $beside) {
        if (Test-Path (Join-Path $d.FullName 'bin/javac.exe')) { return $d.FullName }
    }
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin/javac.exe'))) { return $env:JAVA_HOME }
    throw "No JDK found. Pass -JdkHome <path to a JDK>."
}

$jdk      = Find-Jdk -Explicit $JdkHome
$javac    = Join-Path $jdk 'bin/javac.exe'
$jlink    = Join-Path $jdk 'bin/jlink.exe'
Write-Host "JDK: $jdk"

# ---------------------------------------------------------------- 1. compile
Write-Host "`n[1/6] Compiling the render engine..."
& $javac -version | Out-Null
$binDir = Join-Path $ProjectRoot 'bin'
if (Test-Path $binDir) { Remove-Item $binDir -Recurse -Force }
New-Item -ItemType Directory -Path $binDir | Out-Null
$sources = Get-ChildItem (Join-Path $ProjectRoot 'src') -Recurse -Filter *.java | ForEach-Object { $_.FullName }
Write-Host "      $($sources.Count) source files"
& $javac -encoding UTF-8 -nowarn -d $binDir $sources
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

# ------------------------------------------------------------- 2. java runtime
Write-Host "`n[2/6] Building the trimmed Java runtime (jlink)..."
$bundledRuntime = Join-Path $ProjectRoot 'runtime'
if (Test-Path $bundledRuntime) { Remove-Item $bundledRuntime -Recurse -Force }
& $jlink --add-modules java.base,java.desktop --strip-debug --no-header-files --no-man-pages `
         --compress=zip-6 --output $bundledRuntime
if ($LASTEXITCODE -ne 0) { throw "jlink failed" }

# ------------------------------------------------------------------ 3. stage
Write-Host "`n[3/6] Staging the package..."
if (Test-Path $stageDir) { Remove-Item $stageDir -Recurse -Force }
New-Item -ItemType Directory -Path $stageDir | Out-Null

foreach ($item in @('bin', 'runtime', 'assets')) {
    $src = Join-Path $ProjectRoot $item
    if (-not (Test-Path $src)) { throw "missing $item" }
    Copy-Item $src -Destination $stageDir -Recurse
}
New-Item -ItemType Directory -Path (Join-Path $stageDir 'renders') -Force | Out-Null
foreach ($bat in Get-ChildItem $ProjectRoot -Filter '*.bat') {
    Copy-Item $bat.FullName -Destination $stageDir
}

# ----------------------------------------------------------------- 4. webapp
Write-Host "`n[4/6] Copying the Electron shell..."
$stageWebapp = Join-Path $stageDir 'webapp'
New-Item -ItemType Directory -Path $stageWebapp | Out-Null
foreach ($f in @('main.js', 'preload.js', 'engine-client.js', 'runtime.js', 'package.json')) {
    Copy-Item (Join-Path $webapp $f) -Destination $stageWebapp
}
Copy-Item (Join-Path $webapp 'renderer') -Destination $stageWebapp -Recurse

if (-not $SkipElectron) {
    $electronSrc = Join-Path $webapp 'node_modules/electron'
    if (-not (Test-Path $electronSrc)) { throw "electron not installed; run: cd webapp; npm install electron" }

    # The shell only needs the Electron binary tree plus the electron module's metadata.
    $nmElectron = Join-Path $stageWebapp 'node_modules/electron'
    New-Item -ItemType Directory -Path $nmElectron -Force | Out-Null
    Copy-Item (Join-Path $electronSrc 'package.json') -Destination $nmElectron
    Copy-Item (Join-Path $electronSrc 'dist') -Destination $nmElectron -Recurse
}

# --------------------------------------------------------------- 5. prune
Write-Host "`n[5/6] Pruning non-Windows and unused payloads..."
if (-not $SkipElectron) {
    $dist = Join-Path $stageWebapp 'node_modules/electron/dist'
    # Electron ships every locale and several platform payloads; keep the ones we need.
    $locales = Join-Path $dist 'locales'
    if (Test-Path $locales) {
        Get-ChildItem $locales -Filter '*.pak' |
            Where-Object { $_.Name -notmatch '^(en-US|zh-CN|zh-TW)\.pak$' } |
            Remove-Item -Force
    }
    foreach ($junk in @('LICENSES.chromium.html')) {
        $p = Join-Path $dist $junk
        if (Test-Path $p) { Remove-Item $p -Force }
    }
}
# Engine sources and reports never ship.
foreach ($junk in @('src', 'logs', 'archive', 'tools', 'packs', '.dsh-acl-reports')) {
    $p = Join-Path $stageDir $junk
    if (Test-Path $p) { Remove-Item $p -Recurse -Force }
}

# --------------------------------------------------------------- 6. report
Write-Host "`n[6/6] Package summary"
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
Write-Host "`nReady: $stageDir"
Write-Host "Zip it with:  Compress-Archive -Path '$stageDir' -DestinationPath '$distRoot\LitematicRender.zip' -Force"
