param([Parameter(Mandatory=$true)][string]$Script,[string[]]$TestArguments=@(),[ValidateRange(10,900)][int]$TimeoutSeconds=180)
$ErrorActionPreference='Stop'
$testProject=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$launchInfo=[Diagnostics.ProcessStartInfo]::new()
$launchInfo.FileName=Join-Path $testProject 'webapp/node_modules/electron/dist/electron.exe'
$launchInfo.WorkingDirectory=$testProject
$launchInfo.UseShellExecute=$false
$launchInfo.CreateNoWindow=$true
$launchInfo.RedirectStandardOutput=$true
$launchInfo.RedirectStandardError=$true
$launchInfo.Environment.Remove('ELECTRON_RUN_AS_NODE') | Out-Null
$launchInfo.ArgumentList.Add([IO.Path]::GetFullPath((Join-Path $testProject $Script)))
foreach($testArgument in $TestArguments){$launchInfo.ArgumentList.Add($testArgument)}
$testProcess=[Diagnostics.Process]::new()
$testProcess.StartInfo=$launchInfo
if(-not $testProcess.Start()){throw 'Electron test did not start'}
$stdoutTask=$testProcess.StandardOutput.ReadToEndAsync()
$stderrTask=$testProcess.StandardError.ReadToEndAsync()
$watch=[Diagnostics.Stopwatch]::StartNew()
while(-not $testProcess.WaitForExit(1000)){
    if($watch.Elapsed.TotalSeconds -gt $TimeoutSeconds){$testProcess.Kill($true);throw 'Electron test timed out'}
}
$stdoutTask.GetAwaiter().GetResult()
$stderrTask.GetAwaiter().GetResult()
if($testProcess.ExitCode -ne 0){throw "Electron test failed: $($testProcess.ExitCode)"}
