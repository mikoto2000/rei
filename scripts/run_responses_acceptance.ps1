# Fixed acceptance targets and limits are documented in responses-api-live-validation.md.
# This launcher is opt-in and never reads application.yaml or inherits general environment variables.
param(
 [Parameter(Mandatory=$true)][ValidateSet('openai','local-deepseek')][string]$Target,
 [Parameter(Mandatory=$true)][ValidateSet('core','discovery-verified','discovery-metrics','checkpoint-write','checkpoint-read','approval-write','approval-read','approval-deny-write','approval-deny','features','output-limit','continuity','storage-metrics','local-common','local-structured','local-image','local-summary','local-continuity','local-output-limit','operational-preflight','operational-smoke','operational-short','operational-medium','operational-long','operational-goal','operational-research','operational-planning','operational-subagent','operational-cancel')][string]$Action,
 [Parameter(Mandatory=$true)][string]$EvidenceRoot,
 [Parameter(Mandatory=$true)][switch]$ApprovedExtendedBudget,
 [switch]$ApprovedTimeExtension,
 [string]$JavaExecutable=(Get-Command java -ErrorAction Stop).Source
)
$ErrorActionPreference='Stop'
if (-not $ApprovedExtendedBudget) { throw 'Explicit approval of the documented extended budget required' }
$taskWorktree=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$taskEvidence=[IO.Path]::GetFullPath($EvidenceRoot)
$taskRoot=Join-Path $taskEvidence $Target
$taskMarker=Join-Path $taskRoot 'acceptance-owned.marker'
if ((Test-Path -LiteralPath $taskRoot) -and -not (Test-Path -LiteralPath $taskMarker)) {
 if (@(Get-ChildItem -LiteralPath $taskRoot -Force).Count -gt 0) { throw 'Nonempty directory lacks acceptance ownership marker' }
}
New-Item -ItemType Directory -Force -Path $taskRoot,($taskRoot + '/home'),($taskRoot + '/tmp'),($taskRoot + '/data') | Out-Null
if (Test-Path -LiteralPath $taskMarker) {
 if ((Get-Content -LiteralPath $taskMarker -Raw).Trim() -ne ('responses-extended-' + $Target)) { throw 'Acceptance directory binding mismatch' }
} else { [IO.File]::WriteAllText($taskMarker,('responses-extended-' + $Target)) }
$taskClasspath=$taskWorktree + '/target/test-classes;' + $taskWorktree + '/target/classes;' + (Get-Content -LiteralPath ($taskWorktree + '/target/live-classpath.txt') -Raw).Trim()
$taskStart=[Diagnostics.ProcessStartInfo]::new()
$taskStart.FileName=$JavaExecutable
$taskStart.WorkingDirectory=$taskRoot
$taskStart.UseShellExecute=$false
$taskStart.CreateNoWindow=$true
$taskStart.RedirectStandardOutput=$true
$taskStart.RedirectStandardError=$true
$taskStart.Environment.Clear()
foreach ($taskName in @('SystemRoot','WINDIR')) {
 $taskValue=[Environment]::GetEnvironmentVariable($taskName,'Process')
 if ($taskValue) { $taskStart.Environment[$taskName]=$taskValue }
}
$taskStart.Environment['TEMP']=$taskRoot + '/tmp'
$taskStart.Environment['TMP']=$taskRoot + '/tmp'
$taskStart.Environment['REI_DATA_DIR']=$taskRoot + '/data'
$taskStart.Environment['REI_LIVE_APPROVED_EXTENDED']='true'
if ($ApprovedTimeExtension) { $taskStart.Environment['REI_LIVE_APPROVED_TIME_EXTENSION']='phase9-time-2h' }
$taskKeyName=if ($Target -eq 'openai') { 'OPENAI_API_KEY' } else { 'REI_OPENAI_API_KEY' }
$taskKeyValue=[Environment]::GetEnvironmentVariable($taskKeyName,'Process')
if ($taskKeyValue) { $taskStart.Environment[$taskKeyName]=$taskKeyValue }
$taskMockito=@($taskClasspath.Split(';') | Where-Object { $_ -match '[\\/]mockito-core-[^\\/]+\.jar$' })
if ($taskMockito.Count -ne 1) { throw 'Pinned Mockito agent not uniquely resolved' }
$taskMain=if ($Action.StartsWith('operational-')) { 'dev.mikoto2000.rei.llm.ResponsesOperationalLiveAcceptance' } elseif ($Action.StartsWith('local-')) { 'dev.mikoto2000.rei.llm.ResponsesLocalLiveAcceptance' } else { 'dev.mikoto2000.rei.llm.ResponsesCoreLiveAcceptance' }
$taskArguments=@('--enable-native-access=ALL-UNNAMED','-Xshare:off',('-javaagent:' + $taskMockito[0]),'-Xmx1024m','-Dfile.encoding=UTF-8','-Dspring.devtools.restart.enabled=false',('-Djava.io.tmpdir=' + $taskRoot + '/tmp'),('-Duser.home=' + $taskRoot + '/home'),'-cp',$taskClasspath,$taskMain,$Target,$taskRoot,$Action)
$taskArgumentLines=@($taskArguments | ForEach-Object { '"' + $_.Replace('\','/').Replace('"','\"') + '"' })
$taskArgumentFile=$taskRoot + '/' + $Action + '.java.args'
[IO.File]::WriteAllLines($taskArgumentFile,$taskArgumentLines,[Text.UTF8Encoding]::new($false))
$taskStart.ArgumentList.Add('@' + $taskArgumentFile)
$taskProcess=[Diagnostics.Process]::new()
$taskProcess.StartInfo=$taskStart
$taskWaitMs=1800000
$taskJournal=Join-Path $taskRoot 'budget.jsonl'
if (Test-Path -LiteralPath $taskJournal) {
 $taskRows=@(Get-Content -LiteralPath $taskJournal | ForEach-Object { $_ | ConvertFrom-Json })
 if ($taskRows.Count -eq 0 -or $taskRows[0].type -ne 'H') { throw 'Existing approval header required' }
 $taskExtensions=@($taskRows | Where-Object type -eq 'E')
 if ($taskExtensions.Count -gt 1) { throw 'Stacked time extension rejected' }
 $taskExtraMs=0L
 if ($taskExtensions.Count -eq 1) {
  if ($taskExtensions[0].extension_ms -ne 7200000 -or $taskExtensions[0].approval -ne 'user-20261125-phase9-time-2h') { throw 'Unknown time approval' }
  $taskExtraMs=7200000L
 }
 if ($ApprovedTimeExtension) { $taskExtraMs=7200000L }
 $taskDeadline=[DateTimeOffset]::FromUnixTimeMilliseconds([long]$taskRows[0].started).AddMilliseconds(10800000L + $taskExtraMs)
 $taskRemaining=($taskDeadline - [DateTimeOffset]::UtcNow).TotalMilliseconds
 if ($taskRemaining -le 0) { throw 'Existing approved time window expired; no child started' }
 $taskWaitMs=[int][Math]::Min(1800000,[Math]::Floor($taskRemaining))
}
try {
 $taskStartedAt=[DateTimeOffset]::UtcNow
 [void]$taskProcess.Start()
 $taskOut=$taskProcess.StandardOutput.ReadToEndAsync()
 $taskErr=$taskProcess.StandardError.ReadToEndAsync()
 if (-not $taskProcess.WaitForExit($taskWaitMs)) {
  $taskProcess.Kill($true)
  $taskProcess.WaitForExit()
  throw 'Owned action exceeded its approved wall-clock/process deadline'
 }
 $taskOut.Result | Set-Content -LiteralPath ($taskRoot + '/' + $Action + '.stdout.log') -Encoding utf8
 $taskErr.Result | Set-Content -LiteralPath ($taskRoot + '/' + $Action + '.stderr.log') -Encoding utf8
 $taskExit=$taskProcess.ExitCode
 if ($taskExit -eq 0 -and $Action.StartsWith('operational-')) {
  $taskResults=Join-Path $taskRoot 'results.jsonl'
  $taskReceipts=if (Test-Path -LiteralPath $taskResults) { @(Get-Content -LiteralPath $taskResults | ForEach-Object { $_ | ConvertFrom-Json } | Where-Object { $_.case -eq 'OPERATIONAL_ACTION' -and $_.action -eq $Action -and $_.success -and [DateTimeOffset]::Parse($_.timestamp) -ge $taskStartedAt }) } else { @() }
  if ($taskReceipts.Count -ne 1) { throw 'Operational exit code lacks the new owned terminal receipt' }
 }
 [pscustomobject]@{Target=$Target; Action=$Action; ExitCode=$taskExit}
 exit $taskExit
} finally {
 if ($taskProcess.Id -and -not $taskProcess.HasExited) { $taskProcess.Kill($true) }
 $taskProcess.Dispose()
}
