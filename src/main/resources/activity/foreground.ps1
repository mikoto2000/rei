$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
Add-Type @'
using System;
using System.Text;
using System.Runtime.InteropServices;
public static class ReiActivityForeground {
  [StructLayout(LayoutKind.Sequential)] public struct Rect { public int Left, Top, Right, Bottom; }
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out Rect rect);
  [DllImport("user32.dll")] public static extern IntPtr SetThreadDpiAwarenessContext(IntPtr context);
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetWindowText(IntPtr h, StringBuilder text, int count);
}
'@
$reiWindow = [ReiActivityForeground]::GetForegroundWindow()
[void][ReiActivityForeground]::SetThreadDpiAwarenessContext([IntPtr](-4))
if ($reiWindow -eq [IntPtr]::Zero) { exit 1 }
$reiProcessId = [uint32]0
[void][ReiActivityForeground]::GetWindowThreadProcessId($reiWindow, [ref]$reiProcessId)
$reiProcess = Get-Process -Id $reiProcessId -ErrorAction Stop
$reiTitle = New-Object System.Text.StringBuilder 4096
[void][ReiActivityForeground]::GetWindowText($reiWindow, $reiTitle, $reiTitle.Capacity)
if ($reiWindow -ne [ReiActivityForeground]::GetForegroundWindow()) { exit 1 }
$reiRect = New-Object ReiActivityForeground+Rect
$reiBounds = $null
if ([ReiActivityForeground]::GetWindowRect($reiWindow, [ref]$reiRect)) {
  $reiBounds = @{ x=$reiRect.Left; y=$reiRect.Top; width=($reiRect.Right-$reiRect.Left); height=($reiRect.Bottom-$reiRect.Top) }
}
@{ processName=$reiProcess.ProcessName; processId=[long]$reiProcessId; windowTitle=$reiTitle.ToString(); windowId=$reiWindow.ToInt64().ToString(); bounds=$reiBounds } | ConvertTo-Json -Compress
