# Verifies a build against the data-driven render invariants.
#
#   powershell -File verify.ps1
#
# Runs the shape audit (block culling / AO classification vs vanilla behaviour), a render of the
# reference schematic, and reports unresolved inputs. Exits non-zero on any failure.

param(
    [string]$Schematic = "D:\litematic-display\曼波mis_(咸鱼lite_v2+)_v2.4.2.litematic",
    [string]$JdkHome = "",
    [int]$Size = 1024
)

$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

function Find-Jdk {
    param([string]$Explicit)
    if ($Explicit -and (Test-Path (Join-Path $Explicit 'bin/javac.exe'))) { return $Explicit }
    $beside = Get-ChildItem (Join-Path $PSScriptRoot '..\..') -Directory -Filter 'jdk*' -ErrorAction SilentlyContinue |
              Sort-Object Name -Descending
    foreach ($d in $beside) {
        if (Test-Path (Join-Path $d.FullName 'bin/javac.exe')) { return $d.FullName }
    }
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin/javac.exe'))) { return $env:JAVA_HOME }
    throw "No JDK found. Pass -JdkHome <path>."
}

$jdk    = Find-Jdk -Explicit $JdkHome
$javac  = Join-Path $jdk 'bin/javac.exe'
$java   = Join-Path $jdk 'bin/java.exe'
$fail   = 0

Write-Host "=== building ===" -ForegroundColor Cyan
$sources = Get-ChildItem (Join-Path $PSScriptRoot 'src') -Recurse -Filter *.java | ForEach-Object { $_.FullName }
& $javac -encoding UTF-8 -nowarn -d (Join-Path $PSScriptRoot 'bin') $sources
if ($LASTEXITCODE -ne 0) { Write-Host "BUILD FAILED" -ForegroundColor Red; exit 1 }

$common = @('-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', "-Dlitematicrender.home=$PSScriptRoot",
            '-cp', (Join-Path $PSScriptRoot 'bin'))

Write-Host "`n=== block classification audit ===" -ForegroundColor Cyan
$audit = & $java @common -Xmx4g io.github.litematicrender.DiagShapeAudit (Join-Path $PSScriptRoot 'assets\vanilla') 2>&1
$audit | ForEach-Object { Write-Host $_ }
if ($audit -match 'FAIL') { $fail++ }

Write-Host "`n=== render reference schematic ===" -ForegroundColor Cyan
if (-not (Test-Path $Schematic)) {
    Write-Host "reference schematic not found, skipping render: $Schematic" -ForegroundColor Yellow
} else {
    $out = Join-Path $PSScriptRoot 'renders\_verify\verify.png'
    New-Item -ItemType Directory -Force -Path (Split-Path $out) | Out-Null
    $log = & $java @common -Xmx6g io.github.litematicrender.Main --render $Schematic $out $Size $Size 2>&1
    $log | ForEach-Object { Write-Host $_ }

    if (-not (Test-Path $out)) {
        Write-Host "FAIL: no PNG produced" -ForegroundColor Red; $fail++
    } else {
        $mb = [math]::Round((Get-Item $out).Length / 1KB)
        Write-Host "output: $out ($mb KB)" -ForegroundColor Green
    }

    # Missing models/sprites are a hard failure; missing CJK glyphs are a known limitation.
    $bad = $log | Select-String -Pattern 'no model for blockstate|sprite not in atlas|unresolved texture slot'
    if ($bad) { Write-Host "FAIL: unresolved models/sprites" -ForegroundColor Red; $bad | ForEach-Object { Write-Host "  $_" }; $fail++ }
    $cjk = ($log | Select-String -Pattern 'glyphs the bundled font lacks').Count
    if ($cjk -gt 0) { Write-Host "note: $cjk sign lines need CJK glyphs the bundled font lacks (known)" -ForegroundColor Yellow }
}

Write-Host ""
if ($fail -eq 0) { Write-Host "ALL CHECKS PASSED" -ForegroundColor Green; exit 0 }
Write-Host "$fail CHECK(S) FAILED" -ForegroundColor Red; exit 1
