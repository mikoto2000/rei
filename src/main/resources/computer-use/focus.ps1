$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$stage = 'load_assemblies'
try {
  Add-Type -AssemblyName UIAutomationClient
  Add-Type -AssemblyName UIAutomationTypes
  Add-Type -AssemblyName WindowsBase
  $stage = 'focused_element'
  $pointQuery = $null -ne (Get-Variable reiPointX -ErrorAction SilentlyContinue)
  if ($pointQuery) {
    $stage = 'element_from_point'
    $element = [System.Windows.Automation.AutomationElement]::FromPoint([System.Windows.Point]::new($reiPointX,$reiPointY))
  } else { $element = [System.Windows.Automation.AutomationElement]::FocusedElement }
  if ($null -eq $element) { throw 'No focused element' }
  $stage = 'element_properties'
  $current = $element.Current
  $processName = $null
  try { $processName = (Get-Process -Id $current.ProcessId -ErrorAction Stop).ProcessName } catch { }
  $editable = $null
  $editableReason = 'pattern_unavailable'
  $pattern = $null
  try {
  if ($element.TryGetCurrentPattern([System.Windows.Automation.ValuePattern]::Pattern, [ref]$pattern)) {
    $editable = -not $pattern.Current.IsReadOnly
    $editableReason = 'value_pattern'
  } elseif ($element.TryGetCurrentPattern([System.Windows.Automation.TextPattern]::Pattern, [ref]$pattern)) {
    $readOnly = $pattern.DocumentRange.GetAttributeValue([System.Windows.Automation.TextPattern]::IsReadOnlyAttribute)
    $editableReason = 'read_only_attribute_unavailable'
    if ($readOnly -is [bool]) { $editable = -not $readOnly; $editableReason = 'text_pattern' }
  }
  } catch { $editable = $null; $editableReason = 'pattern_error:' + $_.Exception.GetType().FullName }
  $stage = 'serialize_element'
  if (-not $current.IsEnabled -or $current.IsPassword) { $editable = $false }
  $elementId = $null
  try { $elementId = [string]$current.ProcessId + ':' + ($element.GetRuntimeId() -join '.') } catch { }
  $value = $null
  $valueStatus = 'unavailable'
  if (-not $pointQuery -and -not $current.IsPassword -and $current.HasKeyboardFocus -and $editable -eq $true) {
    try {
      $valuePattern = $null
      if ($element.TryGetCurrentPattern([System.Windows.Automation.ValuePattern]::Pattern,[ref]$valuePattern)) {
        $value = $valuePattern.Current.Value
      } elseif ($element.TryGetCurrentPattern([System.Windows.Automation.TextPattern]::Pattern,[ref]$valuePattern)) {
        $value = $valuePattern.DocumentRange.GetText(10001)
      }
      if ($null -ne $value) {
        $valueStatus = 'ok'
        if ($value.Length -gt 10000) { $value = $value.Substring(0,10000); $valueStatus = 'truncated' }
      }
    } catch { $value = $null; $valueStatus = 'read_error' }
  }
  $name = if ($current.IsPassword) { '' } else { $current.Name }
  if ($name.Length -gt 200) { $name = $name.Substring(0,200) }
  $bounds = $current.BoundingRectangle
  @{
    status='ok'; name=$name; controlType=$current.ControlType.ProgrammaticName
    processId=$current.ProcessId; processName=$processName; probeProcessId=$PID
    probeOwnsFocus=($current.ProcessId -eq $PID); hasKeyboardFocus=$current.HasKeyboardFocus
    editableReason=$editableReason
    queryKind=$(if ($pointQuery) { 'point' } else { 'focus' })
    elementId=$elementId; value=$value; valueStatus=$valueStatus
    enabled=$current.IsEnabled; password=$current.IsPassword; editable=$editable
    bounds=@{x=$bounds.X; y=$bounds.Y; width=$bounds.Width; height=$bounds.Height}
  } | ConvertTo-Json -Depth 4 -Compress
} catch {
  @{status='unknown'; reason='uia_exception'; stage=$stage; exceptionType=$_.Exception.GetType().FullName;
    message=$_.Exception.Message.Substring(0,[Math]::Min(300,$_.Exception.Message.Length)); probeProcessId=$PID} | ConvertTo-Json -Compress
}
