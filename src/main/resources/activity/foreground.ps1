$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
Add-Type @'
using System;
using System.Text;
using System.Runtime.InteropServices;
public static class ReiActivityForeground {
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetWindowText(IntPtr h, StringBuilder text, int count);
}
'@
$reiWindow = [ReiActivityForeground]::GetForegroundWindow()
if ($reiWindow -eq [IntPtr]::Zero) { exit 1 }
$reiProcessId = [uint32]0
[void][ReiActivityForeground]::GetWindowThreadProcessId($reiWindow, [ref]$reiProcessId)
$reiProcess = Get-Process -Id $reiProcessId -ErrorAction Stop
$reiTitle = New-Object System.Text.StringBuilder 4096
[void][ReiActivityForeground]::GetWindowText($reiWindow, $reiTitle, $reiTitle.Capacity)
if ($reiWindow -ne [ReiActivityForeground]::GetForegroundWindow()) { exit 1 }
@{ processName=$reiProcess.ProcessName; processId=[long]$reiProcessId; windowTitle=$reiTitle.ToString(); windowId=$reiWindow.ToInt64().ToString() } | ConvertTo-Json -Compress
