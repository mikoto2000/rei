# Fixed acceptance targets and limits are documented in responses-api-live-validation.md.
# This launcher is opt-in and never reads application.yaml or inherits general environment variables.
param(
 [Parameter(Mandatory=$true)][ValidateSet('openai','local-deepseek')][string]$Target,
 [Parameter(Mandatory=$true)][ValidateSet('core','discovery-verified','discovery-metrics','checkpoint-write','checkpoint-read','approval-write','approval-read','approval-deny-write','approval-deny','features','output-limit','continuity','storage-metrics')][string]$Action,
 [Parameter(Mandatory=$true)][string]$EvidenceRoot,
 [Parameter(Mandatory=$true)][switch]$ApprovedExtendedBudget,
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
$taskKeyName=if ($Target -eq 'openai') { 'OPENAI_API_KEY' } else { 'REI_OPENAI_API_KEY' }
$taskKeyValue=[Environment]::GetEnvironmentVariable($taskKeyName,'Process')
if ($taskKeyValue) { $taskStart.Environment[$taskKeyName]=$taskKeyValue }
$taskMockito=@($taskClasspath.Split(';') | Where-Object { $_ -match '[\\/]mockito-core-[^\\/]+\.jar$' })
if ($taskMockito.Count -ne 1) { throw 'Pinned Mockito agent not uniquely resolved' }
$taskArguments=@('--enable-native-access=ALL-UNNAMED','-Xshare:off',('-javaagent:' + $taskMockito[0]),'-Xmx1024m','-Dfile.encoding=UTF-8',('-Djava.io.tmpdir=' + $taskRoot + '/tmp'),('-Duser.home=' + $taskRoot + '/home'),'-cp',$taskClasspath,'dev.mikoto2000.rei.llm.ResponsesCoreLiveAcceptance',$Target,$taskRoot,$Action)
$taskArgumentLines=@($taskArguments | ForEach-Object { '"' + $_.Replace('\','/').Replace('"','\"') + '"' })
$taskArgumentFile=$taskRoot + '/' + $Action + '.java.args'
[IO.File]::WriteAllLines($taskArgumentFile,$taskArgumentLines,[Text.UTF8Encoding]::new($false))
$taskStart.ArgumentList.Add('@' + $taskArgumentFile)
$taskProcess=[Diagnostics.Process]::new()
$taskProcess.StartInfo=$taskStart
try {
 [void]$taskProcess.Start()
 $taskOut=$taskProcess.StandardOutput.ReadToEndAsync()
 $taskErr=$taskProcess.StandardError.ReadToEndAsync()
 if (-not $taskProcess.WaitForExit(1800000)) {
  $taskProcess.Kill($true)
  $taskProcess.WaitForExit()
  throw 'Owned extended action exceeded 30min process deadline'
 }
 $taskOut.Result | Set-Content -LiteralPath ($taskRoot + '/' + $Action + '.stdout.log') -Encoding utf8
 $taskErr.Result | Set-Content -LiteralPath ($taskRoot + '/' + $Action + '.stderr.log') -Encoding utf8
 $taskExit=$taskProcess.ExitCode
 [pscustomobject]@{Target=$Target; Action=$Action; ExitCode=$taskExit}
 exit $taskExit
} finally {
 if ($taskProcess.Id -and -not $taskProcess.HasExited) { $taskProcess.Kill($true) }
 $taskProcess.Dispose()
}
