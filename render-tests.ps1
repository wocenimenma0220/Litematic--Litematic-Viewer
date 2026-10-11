# Renders every test fixture so the output can be compared against Minecraft by eye.
#
#   powershell -File render-tests.ps1 [-Size 1024] [-Only axes]
#
# Each fixture isolates one question (camera basis, face culling, fluids, layer order, AO);
# see DiagGenSchematic for what each one contains.

param(
    [string]$JdkHome = "",
    [int]$Size = 900,
    [string]$Only = "",
    [double]$Rotation = 45,
    [double]$Slant = 30
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

$jdk   = Find-Jdk -Explicit $JdkHome
$java  = Join-Path $jdk 'bin/java.exe'
$bin   = Join-Path $PSScriptRoot 'bin'
$tests = Join-Path $PSScriptRoot 'schematics\test'
$out   = Join-Path $PSScriptRoot 'renders\tests'
New-Item -ItemType Directory -Force -Path $tests, $out | Out-Null

# Generate the fixtures if missing (or always, so they track the generator).
& $java "-Dfile.encoding=UTF-8" "-Dstdout.encoding=UTF-8" "-Dlitematicrender.home=$PSScriptRoot" `
    -cp $bin io.github.litematicrender.DiagGenSchematic $tests | Out-Host

$fixtures = Get-ChildItem $tests -Filter *.litematic | Sort-Object Name
if ($Only) { $fixtures = $fixtures | Where-Object { $_.BaseName -like "*$Only*" } }

$common = @('-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', "-Dlitematicrender.home=$PSScriptRoot", '-cp', $bin)

foreach ($f in $fixtures) {
    $png = Join-Path $out ($f.BaseName + '.png')
    Write-Host "render $($f.BaseName) ..." -ForegroundColor Cyan
    $log = & $java @common -Xmx2g io.github.litematicrender.Main --render $f.FullName $png $Size $Size 2>&1
    $bad = $log | Select-String -Pattern 'no model for blockstate|sprite not in atlas|unresolved texture slot'
    if ($bad) {
        Write-Host "  UNRESOLVED:" -ForegroundColor Red
        $bad | ForEach-Object { Write-Host "    $_" -ForegroundColor Red }
    }
}

Write-Host ""
Write-Host "renders in $out" -ForegroundColor Green
Get-ChildItem $out -Filter *.png | ForEach-Object { "  {0,-24} {1,8:N0} KB" -f $_.Name, ($_.Length / 1KB) }
