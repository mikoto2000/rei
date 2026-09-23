$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
Add-Type @'
using System;
using System.Text;
using System.Collections.Generic;
using System.Runtime.InteropServices;
public static class ReiActivityMetadata {
  [StructLayout(LayoutKind.Sequential)] public struct Rect { public int Left, Top, Right, Bottom; }
  [StructLayout(LayoutKind.Sequential, CharSet=CharSet.Unicode)] public struct MonitorInfo {
    public int Size; public Rect Monitor, Work; public uint Flags;
    [MarshalAs(UnmanagedType.ByValTStr, SizeConst=32)] public string Device;
  }
  public delegate bool EnumProc(IntPtr h, IntPtr l);
  [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc proc, IntPtr l);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
  [DllImport("user32.dll")] public static extern bool IsIconic(IntPtr h);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out Rect rect);
  [DllImport("user32.dll")] public static extern IntPtr SetThreadDpiAwarenessContext(IntPtr context);
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetWindowText(IntPtr h, StringBuilder text, int count);
  [DllImport("user32.dll")] public static extern IntPtr MonitorFromWindow(IntPtr h, uint flags);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern bool GetMonitorInfo(IntPtr h, ref MonitorInfo info);
  [DllImport("dwmapi.dll")] public static extern int DwmGetWindowAttribute(IntPtr h, uint attribute, out int value, int size);
  public static IntPtr[] Windows() {
    var found=new List<IntPtr>();
    EnumWindows(delegate(IntPtr h, IntPtr l) { if(IsWindowVisible(h) && !IsIconic(h)) found.Add(h); return found.Count<256; },IntPtr.Zero);
    return found.ToArray();
  }
}
'@
[void][ReiActivityMetadata]::SetThreadDpiAwarenessContext([IntPtr](-4))
$reiForeground = [ReiActivityMetadata]::GetForegroundWindow()
if ($reiForeground -eq [IntPtr]::Zero) { exit 1 }
function Read-ReiWindow([IntPtr]$handle) {
  $reiPid = [uint32]0
  [void][ReiActivityMetadata]::GetWindowThreadProcessId($handle, [ref]$reiPid)
  $reiProcess = Get-Process -Id $reiPid -ErrorAction Stop
  $reiText = New-Object System.Text.StringBuilder 4096
  [void][ReiActivityMetadata]::GetWindowText($handle, $reiText, $reiText.Capacity)
  $reiRect = New-Object ReiActivityMetadata+Rect
  $reiBounds = $null
  if ([ReiActivityMetadata]::GetWindowRect($handle, [ref]$reiRect)) {
    $reiBounds = @{ x=$reiRect.Left; y=$reiRect.Top; width=($reiRect.Right-$reiRect.Left); height=($reiRect.Bottom-$reiRect.Top) }
  }
  return @{processName=$reiProcess.ProcessName; processId=[long]$reiPid; windowTitle=$reiText.ToString(); windowId=$handle.ToInt64().ToString(); bounds=$reiBounds}
}
$reiFront = Read-ReiWindow $reiForeground
$reiVisible = @()
foreach ($reiHandle in [ReiActivityMetadata]::Windows()) {
  if ($reiHandle -eq $reiForeground) { continue }
  try {
    $reiCloaked = 0
    if ([ReiActivityMetadata]::DwmGetWindowAttribute($reiHandle,14,[ref]$reiCloaked,4) -eq 0 -and $reiCloaked -ne 0) { continue }
    $reiMonitor = [ReiActivityMetadata]::MonitorFromWindow($reiHandle,0)
    if ($reiMonitor -eq [IntPtr]::Zero) { continue }
    $reiInfo = New-Object ReiActivityMetadata+MonitorInfo
    $reiInfo.Size = [System.Runtime.InteropServices.Marshal]::SizeOf($reiInfo)
    if (-not [ReiActivityMetadata]::GetMonitorInfo($reiMonitor,[ref]$reiInfo)) { continue }
    $reiItem = Read-ReiWindow $reiHandle
    if ($null -eq $reiItem.bounds -or $reiItem.bounds.width -le 0 -or $reiItem.bounds.height -le 0) { continue }
    $reiVisible += @{window=$reiItem; visible=$true; minimized=$false; offScreen=$false; monitor=$reiInfo.Device}
    if ($reiVisible.Count -ge 32) { break }
  } catch { continue }
}
if ($reiForeground -ne [ReiActivityMetadata]::GetForegroundWindow()) { exit 1 }
@{foreground=$reiFront; visibleWindows=@($reiVisible)} | ConvertTo-Json -Depth 6 -Compress
