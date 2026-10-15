param([string]$Schematic='D:\litematic-display\璃月港.litematic',[ValidatePattern('^[1-9][0-9]*m$')][string]$HeapLimit='1536m')
$ErrorActionPreference='Stop'
$benchmarkRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$benchmarkOutput=Join-Path $PSScriptRoot 'lod-large-output'
New-Item -ItemType Directory -Path $benchmarkOutput -Force | Out-Null
if(Test-Path -LiteralPath (Join-Path $benchmarkOutput 'result.json')){Remove-Item -LiteralPath (Join-Path $benchmarkOutput 'result.json') -Force}
$benchmarkLaunch=[Diagnostics.ProcessStartInfo]::new()
$benchmarkLaunch.FileName='D:\litematic-display\jdk-25.0.4.1+1\bin\java.exe'
$benchmarkLaunch.WorkingDirectory=$benchmarkRoot
$benchmarkLaunch.UseShellExecute=$false;$benchmarkLaunch.CreateNoWindow=$true
$benchmarkLaunch.RedirectStandardOutput=$true;$benchmarkLaunch.RedirectStandardError=$true
foreach($benchmarkArgument in @("-Xmx$HeapLimit",'-cp','bin;tools/verify/lod-output','LodLargeBenchmark',$benchmarkRoot,$Schematic,$benchmarkOutput)){$benchmarkLaunch.ArgumentList.Add($benchmarkArgument)}
$benchmarkProcess=[Diagnostics.Process]::new();$benchmarkProcess.StartInfo=$benchmarkLaunch
if(-not $benchmarkProcess.Start()){throw 'Benchmark did not start'}
$benchmarkStdout=$benchmarkProcess.StandardOutput.ReadToEndAsync();$benchmarkStderr=$benchmarkProcess.StandardError.ReadToEndAsync()
$benchmarkWatch=[Diagnostics.Stopwatch]::StartNew();$benchmarkPeak=0L
while(-not $benchmarkProcess.WaitForExit(1000)) {
    if($benchmarkWatch.Elapsed.TotalSeconds -gt 900){$benchmarkProcess.Kill($true);throw 'Large benchmark timed out'}
    $benchmarkProcess.Refresh();$benchmarkPeak=[Math]::Max($benchmarkPeak,$benchmarkProcess.WorkingSet64)
    [PSCustomObject]@{pid=$benchmarkProcess.Id;peakWorkingSet=$benchmarkPeak;workingSet=$benchmarkProcess.WorkingSet64;seconds=$benchmarkWatch.Elapsed.TotalSeconds}|ConvertTo-Json -Compress|Set-Content -LiteralPath (Join-Path $benchmarkOutput 'memory.json') -Encoding utf8
}
$benchmarkStdout.GetAwaiter().GetResult();$benchmarkStderr.GetAwaiter().GetResult()
if($benchmarkProcess.ExitCode -ne 0){throw "Large benchmark failed: $($benchmarkProcess.ExitCode)"}
