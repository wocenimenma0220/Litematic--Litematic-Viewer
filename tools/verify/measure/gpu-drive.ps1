# Drives the packaged app with real mouse input and records per-PID GPU 3D engine utilisation.
param(
  [Parameter(Mandatory=$true)][string]$AppExe,
  [Parameter(Mandatory=$true)][string]$Schematic,
  [Parameter(Mandatory=$true)][string]$AppRoot
)
$ErrorActionPreference = 'Continue'

Add-Type @"
using System;
using System.Runtime.InteropServices;
public class M {
  [DllImport("user32.dll")] public static extern bool SetCursorPos(int x,int y);
  [DllImport("user32.dll")] public static extern void mouse_event(uint f,uint dx,uint dy,uint d,IntPtr e);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out R r);
  public struct R { public int L,T,Rt,B; }
  public const uint LDOWN=0x0002, LUP=0x0004;
  public static void Drag(int x0,int y0,int x1,int y1,int steps){
    SetCursorPos(x0,y0); System.Threading.Thread.Sleep(20);
    mouse_event(LDOWN,0,0,0,IntPtr.Zero);
    for(int i=1;i<=steps;i++){ SetCursorPos(x0+(x1-x0)*i/steps, y0+(y1-y0)*i/steps); System.Threading.Thread.Sleep(8); }
    mouse_event(LUP,0,0,0,IntPtr.Zero);
  }
}
"@

function Get-Gpu3d([int[]]$pids){
  $rows = Get-CimInstance Win32_PerfFormattedData_GPUPerformanceCounters_GPUEngine -ErrorAction SilentlyContinue |
          Where-Object { $_.Name -match 'engtype_3D' }
  $sum = 0
  foreach($r in $rows){
    if($r.Name -match 'pid_(\d+)_'){ if($pids -contains [int]$Matches[1]){ $sum += [double]$r.UtilizationPercentage } }
  }
  return $sum
}

$before = @(Get-Process electron,java -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Id)
Start-Process -FilePath $AppExe -ArgumentList "`"$Schematic`"" | Out-Null

# Wait for the preview to be ready (log line from the renderer).
$log = Join-Path $AppRoot 'logs\app.log'
$deadline = (Get-Date).AddSeconds(150)
while((Get-Date) -lt $deadline){
  Start-Sleep -Seconds 2
  $tail = Get-Content $log -Tail 3 -ErrorAction SilentlyContinue
  if($tail -match 'preview ready'){ break }
}
Start-Sleep -Seconds 3

$ids = @(Get-Process electron,java -ErrorAction SilentlyContinue | Where-Object { $before -notcontains $_.Id })
Write-Output ("进程: " + (($ids | ForEach-Object { "$($_.ProcessName)#$($_.Id)" }) -join ', '))
$pids = @($ids | Select-Object -ExpandProperty Id)

# Locate the app window (the Electron browser window).
$win = Get-Process -Id ($ids | Where-Object { $_.ProcessName -eq 'electron' } | Select-Object -First 1 -ExpandProperty Id) -ErrorAction SilentlyContinue
$rect = New-Object M+R
$hwnd = [IntPtr]::Zero
foreach($p in ($ids | Where-Object ProcessName -eq 'electron')){
  $pr = Get-Process -Id $p.Id
  if($pr.MainWindowHandle -ne 0){ $hwnd = $pr.MainWindowHandle; break }
}
Write-Output "window handle: $hwnd"
if($hwnd -ne [IntPtr]::Zero){ [void][M]::GetWindowRect($hwnd,[ref]$rect); [void][M]::SetForegroundWindow($hwnd); Start-Sleep -Seconds 1 }

$cx = [int](($rect.L + $rect.Rt)/2); $cy = [int](($rect.T + $rect.B)/2)
Write-Output "viewport center: $cx,$cy"

Write-Output ''
Write-Output '--- 空闲采样（不输入）---'
$idle = @(); for($i=0;$i -lt 8;$i++){ $idle += Get-Gpu3d $pids; Start-Sleep -Milliseconds 500 }
Write-Output ("3D util %: " + ($idle -join ', '))
Write-Output ("空闲最大: {0:N1}%" -f ($idle | Measure-Object -Maximum).Maximum)

Write-Output ''
Write-Output '--- 拖动旋转采样 ---'
$busy = @()
for($k=0;$k -lt 14;$k++){
  if($hwnd -ne [IntPtr]::Zero){
    [M]::Drag($cx-260,$cy, $cx+260,$cy, 26)
    [M]::Drag($cx+260,$cy, $cx-260,$cy, 26)
  }
  $busy += Get-Gpu3d $pids
}
Write-Output ("3D util %: " + ($busy -join ', '))
Write-Output ("拖动最大: {0:N1}%  平均: {1:N1}%" -f ($busy|Measure-Object -Maximum).Maximum, ($busy|Measure-Object -Average).Average)

Write-Output ''
Write-Output '--- 同时段其它进程对照 ---'
Get-CimInstance Win32_PerfFormattedData_GPUPerformanceCounters_GPUEngine -ErrorAction SilentlyContinue |
  Where-Object { $_.Name -match 'engtype_3D' -and [double]$_.UtilizationPercentage -gt 0 } |
  Select-Object Name,UtilizationPercentage | Format-Table -AutoSize
