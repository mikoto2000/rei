param([Parameter(Mandatory=$true)][string]$CaseRoot)
$ErrorActionPreference='Stop'
# Windows PowerShell hosts the OS .NET Framework UI Automation assemblies.
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes
$caseName=Split-Path -Leaf $CaseRoot
if ($caseName -notmatch '^windows-terminal-e2e-[a-f0-9-]{36}$') { throw 'Not a dedicated E2E case' }
$windows=@(Get-Process WindowsTerminal -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowTitle -like ('*'+$caseName) })
if ($windows.Count -ne 1) { throw 'Dedicated fixture window could not be uniquely identified' }
$window=$windows[0]
$element=[System.Windows.Automation.AutomationElement]::FromHandle($window.MainWindowHandle)
if ($element.Current.ProcessId -ne $window.Id -or $element.Current.Name -notlike ('*'+$caseName)) { throw 'Fixture window identity changed' }
$buttons=$element.FindAll([System.Windows.Automation.TreeScope]::Descendants,[System.Windows.Automation.PropertyCondition]::new([System.Windows.Automation.AutomationElement]::ControlTypeProperty,[System.Windows.Automation.ControlType]::Button))
$before=@($buttons | ForEach-Object { @{name=$_.Current.Name;automationId=$_.Current.AutomationId} })
@{windowPid=$window.Id;windowTitle=$element.Current.Name;buttons=$before;action='UI Automation WindowPattern.Close on the dedicated fixture window'} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $CaseRoot 'gui-close-before.json') -Encoding UTF8
$handle=$element.Current.NativeWindowHandle
$condition=[System.Windows.Automation.PropertyCondition]::new([System.Windows.Automation.AutomationElement]::NativeWindowHandleProperty,$handle)
$pattern=$element.GetCurrentPattern([System.Windows.Automation.WindowPattern]::Pattern)
$pattern.Close()
$deadline=[DateTime]::UtcNow.AddSeconds(10)
while ([DateTime]::UtcNow -lt $deadline) {
  $element=[System.Windows.Automation.AutomationElement]::RootElement.FindFirst([System.Windows.Automation.TreeScope]::Children,$condition)
  if ($null -eq $element) { break }
  if ($element.Current.ProcessId -ne $window.Id -or $element.Current.Name -notlike ('*'+$caseName)) { throw 'Fixture window identity changed during close' }
  $buttons=$element.FindAll([System.Windows.Automation.TreeScope]::Descendants,[System.Windows.Automation.PropertyCondition]::new([System.Windows.Automation.AutomationElement]::ControlTypeProperty,[System.Windows.Automation.ControlType]::Button))
  $confirmation=@($buttons | Where-Object { $_.Current.AutomationId -eq 'PrimaryButton' -or $_.Current.Name -in @('Close all','すべて閉じる') })
  if ($confirmation.Count -eq 1) {
    @{name=$confirmation[0].Current.Name;automationId=$confirmation[0].Current.AutomationId;windowPid=$window.Id} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $CaseRoot 'gui-close-confirmation.json') -Encoding UTF8
    $confirmation[0].GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern).Invoke()
  }
  Start-Sleep -Milliseconds 100
}
$remaining=[System.Windows.Automation.AutomationElement]::RootElement.FindFirst([System.Windows.Automation.TreeScope]::Children,$condition)
if ($null -ne $remaining) { throw 'Fixture window did not close through GUI automation' }
@{result='CLOSED';windowPid=$window.Id;caseRoot=$CaseRoot} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $CaseRoot 'gui-close-after.json') -Encoding UTF8
