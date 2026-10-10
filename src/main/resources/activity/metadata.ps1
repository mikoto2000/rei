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
  [StructLayout(LayoutKind.Sequential, CharSet=CharSet.Unicode)] public struct DisplayDevice {
    public int Size;
    [MarshalAs(UnmanagedType.ByValTStr, SizeConst=32)] public string Name;
    [MarshalAs(UnmanagedType.ByValTStr, SizeConst=128)] public string Description;
    public uint Flags;
    [MarshalAs(UnmanagedType.ByValTStr, SizeConst=128)] public string Id;
    [MarshalAs(UnmanagedType.ByValTStr, SizeConst=128)] public string Key;
  }
  public delegate bool MonitorProc(IntPtr monitor, IntPtr hdc, ref Rect bounds, IntPtr data);
  [DllImport("user32.dll")] public static extern bool EnumDisplayMonitors(IntPtr hdc,IntPtr clip,MonitorProc callback,IntPtr data);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern bool EnumDisplayDevices(string name,uint index,ref DisplayDevice device,uint flags);
  public static string InterfaceId(string name) {
    string result=null;
    for(uint i=0;i<16;i++) {
      var device=new DisplayDevice();device.Size=Marshal.SizeOf(typeof(DisplayDevice));
      if(!EnumDisplayDevices(name,i,ref device,1))return result;
      if((device.Flags&1)!=0 && !String.IsNullOrEmpty(device.Id)) {
        if(result!=null)return null;
        result=device.Id;
      }
    }
    return null;
  }
  public static IntPtr[] Monitors() {
    var found=new List<IntPtr>();
    bool ok=EnumDisplayMonitors(IntPtr.Zero,IntPtr.Zero,delegate(IntPtr h,IntPtr d,ref Rect r,IntPtr data) {
      found.Add(h);return found.Count<16;
    },IntPtr.Zero);
    if(!ok)Complete=false;
    return found.ToArray();
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
  public static bool Complete = true;
  public static IntPtr[] Windows() {
    var found=new List<IntPtr>();
    bool ok=EnumWindows(delegate(IntPtr h, IntPtr l) { if(IsWindowVisible(h) && !IsIconic(h)) found.Add(h); if(found.Count>=256) {Complete=false;return false;} return true; },IntPtr.Zero);
    if(!ok) Complete=false;
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
$reiComplete = $true
$reiMonitors = @()
$reiMonitorIds = @{}
foreach ($reiHandle in [ReiActivityMetadata]::Monitors()) {
  $reiInfo = New-Object ReiActivityMetadata+MonitorInfo
  $reiInfo.Size = [System.Runtime.InteropServices.Marshal]::SizeOf($reiInfo)
  if (-not [ReiActivityMetadata]::GetMonitorInfo($reiHandle,[ref]$reiInfo)) { $reiComplete=$false; continue }
  $reiId = [ReiActivityMetadata]::InterfaceId($reiInfo.Device)
  if ([string]::IsNullOrWhiteSpace($reiId)) { $reiComplete=$false; continue }
  $reiMonitorIds[$reiInfo.Device] = $reiId
  $reiMonitors += @{id=$reiId; bounds=@{x=$reiInfo.Monitor.Left; y=$reiInfo.Monitor.Top; width=($reiInfo.Monitor.Right-$reiInfo.Monitor.Left); height=($reiInfo.Monitor.Bottom-$reiInfo.Monitor.Top)}}
}
foreach ($reiHandle in [ReiActivityMetadata]::Windows()) {
  if ($reiHandle -eq $reiForeground) { continue }
  try {
    $reiCloaked = 0
    if ([ReiActivityMetadata]::DwmGetWindowAttribute($reiHandle,14,[ref]$reiCloaked,4) -eq 0 -and $reiCloaked -ne 0) { continue }
    $reiMonitor = [ReiActivityMetadata]::MonitorFromWindow($reiHandle,0)
    if ($reiMonitor -eq [IntPtr]::Zero) { $reiComplete=$false; continue }
    $reiInfo = New-Object ReiActivityMetadata+MonitorInfo
    $reiInfo.Size = [System.Runtime.InteropServices.Marshal]::SizeOf($reiInfo)
    if (-not [ReiActivityMetadata]::GetMonitorInfo($reiMonitor,[ref]$reiInfo)) { $reiComplete=$false; continue }
    if (-not $reiMonitorIds.ContainsKey($reiInfo.Device)) { $reiComplete=$false; continue }
    $reiItem = Read-ReiWindow $reiHandle
    if ($null -eq $reiItem.bounds -or $reiItem.bounds.width -le 0 -or $reiItem.bounds.height -le 0) { $reiComplete=$false; continue }
    $reiVisible += @{window=$reiItem; visible=$true; minimized=$false; offScreen=$false; monitor=$reiMonitorIds[$reiInfo.Device]}
    if ($reiVisible.Count -ge 256) { $reiComplete=$false; break }
  } catch { $reiComplete=$false; continue }
}
if ($reiForeground -ne [ReiActivityMetadata]::GetForegroundWindow()) { exit 1 }
@{foreground=$reiFront; visibleWindows=@($reiVisible); complete=($reiComplete -and [ReiActivityMetadata]::Complete); monitors=@($reiMonitors)} | ConvertTo-Json -Depth 6 -Compress
