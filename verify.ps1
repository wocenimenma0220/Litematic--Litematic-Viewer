# Smoke-tests a build: compiles the engine, renders the reference schematic from several camera
# angles, and fails on anything the renderer reports as unresolved.
#
#   powershell -File verify.ps1
#
# This is the check to run before packaging. It deliberately uses only the shipped engine
# (`io.github.litematicrender.Main`) — the throwaway diagnostic entry points that used to live in
# `src/` have been removed, so verification goes through the same code path the application does.

param(
    # Empty means "find any .litematic in the workspace root". Hardcoding a path here is fragile:
    # the reference schematic has a Chinese filename, and Windows PowerShell 5.1 reads a
    # BOM-less script using the ANSI code page, so a non-ASCII literal can arrive mangled and the
    # script then silently skips the render while still reporting success.
    [string]$Schematic = "",
    [string]$JdkHome = "",
    [int]$Size = 900
)

$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

# Locate the reference schematic without relying on any non-ASCII literal in this file.
if (-not $Schematic) {
    $searchDirs = @(
        (Join-Path $PSScriptRoot '..\..'),   # the workspace root
        (Join-Path $PSScriptRoot 'schematics'),
        $PSScriptRoot
    )
    foreach ($dir in $searchDirs) {
        if (-not (Test-Path $dir)) { continue }
        $hit = Get-ChildItem $dir -Filter '*.litematic' -File -ErrorAction SilentlyContinue |
               Sort-Object Length -Descending | Select-Object -First 1
        if ($hit) { $Schematic = $hit.FullName; break }
    }
}

# Compiling needs `javac` only; the trimmed runtime is not rebuilt here, so a JDK without `jmods`
# is acceptable.
function Find-Jdk {
    param([string]$Explicit)
    if ($Explicit -and (Test-Path (Join-Path $Explicit 'bin/javac.exe'))) { return $Explicit }
    $beside = Get-ChildItem (Join-Path $PSScriptRoot '..\..') -Directory -Filter 'jdk*' -ErrorAction SilentlyContinue |
              Sort-Object Name -Descending
    foreach ($d in $beside) {
        if (Test-Path (Join-Path $d.FullName 'bin/javac.exe')) { return $d.FullName }
    }
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin/javac.exe'))) { return $env:JAVA_HOME }
    # Some Minecraft runtime distributions also include javac.
    $mc = Join-Path $env:APPDATA '.minecraft\runtime'
    if (Test-Path $mc) {
        foreach ($d in Get-ChildItem $mc -Directory -ErrorAction SilentlyContinue) {
            $cand = Join-Path $d.FullName 'bin\javac.exe'
            if (Test-Path $cand) { return $d.FullName }
        }
    }
    throw "No JDK found. Pass -JdkHome <path>."
}

$jdk   = Find-Jdk -Explicit $JdkHome
$javac = Join-Path $jdk 'bin\javac.exe'
$java  = Join-Path $jdk 'bin\java.exe'
$fail  = 0

Write-Host "=== building ===" -ForegroundColor Cyan
Write-Host "JDK: $jdk"
$bin = Join-Path $PSScriptRoot 'bin'
$sources = Get-ChildItem (Join-Path $PSScriptRoot 'src') -Recurse -Filter *.java | ForEach-Object { $_.FullName }
& $javac --release 21 -encoding UTF-8 -nowarn -d $bin $sources
if ($LASTEXITCODE -ne 0) { Write-Host "BUILD FAILED" -ForegroundColor Red; exit 1 }
Write-Host "      $($sources.Count) source files compiled" -ForegroundColor Green

$common = @('-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', "-Dlitematicrender.home=$PSScriptRoot", '-cp', $bin)

Write-Host "`n=== render reference schematic from several angles ===" -ForegroundColor Cyan
if (-not $Schematic -or -not (Test-Path $Schematic)) {
    Write-Host "FAIL: no reference .litematic found to render" -ForegroundColor Red
    $fail++
} else {
    Write-Host "schematic: $Schematic"
    $outDir = Join-Path $PSScriptRoot 'tools\verify\output'
    New-Item -ItemType Directory -Force -Path $outDir | Out-Null
    $allLog = @()
    $hashes = @{}
    foreach ($angle in @(@(45, 30), @(135, 30), @(225, 30), @(315, 30))) {
        $rot = $angle[0]; $slant = $angle[1]
        $out = Join-Path $outDir ("verify_rot{0}_slant{1}.png" -f $rot, $slant)
        if (Test-Path -LiteralPath $out) { Remove-Item -LiteralPath $out -Force }
        Write-Host "  rot=$rot slant=$slant ..." -NoNewline
        $log = & $java @common -Xmx6g io.github.litematicrender.Main --render $Schematic $out $Size $Size $rot $slant 2>&1
        $renderExit = $LASTEXITCODE
        $allLog += $log
        $log | Set-Content -LiteralPath (Join-Path $outDir ("verify_rot{0}.log" -f $rot)) -Encoding UTF8
        if ($renderExit -eq 0 -and (Test-Path $out)) {
            $h = (Get-FileHash $out -Algorithm SHA256).Hash
            $hashes[$rot] = $h
            Write-Host (" {0:N0} KB  {1}" -f ((Get-Item $out).Length / 1KB), $h.Substring(0, 12)) -ForegroundColor Green
        } else {
            Write-Host " FAILED: exit=$renderExit" -ForegroundColor Red
            $log | Select-Object -Last 12 | ForEach-Object { Write-Host $_ }
            $fail++
        }
    }

    # Four camera angles must not produce the identical image. They did once: the CLI accepted
    # width/height but silently ignored rotation, so this loop rendered the same view four times
    # and reported success. A shared hash is the cheap way to catch that class of regression.
    $distinct = ($hashes.Values | Sort-Object -Unique).Count
    if ($hashes.Count -gt 0 -and $distinct -lt $hashes.Count) {
        Write-Host "`nFAIL: $($hashes.Count) angles produced only $distinct distinct image(s) — rotation is being ignored" -ForegroundColor Red
        $fail++
    }

    # A missing model or sprite means the render silently fell back to the pink/black missingno
    # texture. That is a hard failure: it is exactly the kind of regression this script exists for.
    $bad = $allLog | Select-String -Pattern 'no model for blockstate|sprite not in atlas|unresolved texture slot'
    if ($bad) {
        Write-Host "`nFAIL: unresolved models/sprites" -ForegroundColor Red
        $bad | Select-Object -First 20 | ForEach-Object { Write-Host "  $_" }
        $fail++
    }
    $cjk = ($allLog | Select-String -Pattern 'glyphs the bundled font lacks').Count
    if ($cjk -gt 0) {
        Write-Host "note: $cjk sign lines need CJK glyphs the bundled font lacks (known limitation)" -ForegroundColor Yellow
    }
}

Write-Host ""
if ($fail -eq 0) { Write-Host "ALL CHECKS PASSED" -ForegroundColor Green; exit 0 }
Write-Host "$fail CHECK(S) FAILED" -ForegroundColor Red; exit 1
