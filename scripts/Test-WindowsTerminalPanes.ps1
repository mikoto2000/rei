param([ValidateSet('Coordinator','First','Second')][string]$Role='Coordinator', [string]$CaseRoot, [string]$BackendJar, [switch]$GuiClose)
$ErrorActionPreference='Stop'
if (-not $IsWindows) { throw 'Windows Terminal E2E requires PowerShell 7 on Windows' }
$repoRoot=Split-Path -Parent $PSScriptRoot
if (-not $BackendJar) { $BackendJar=Join-Path $repoRoot 'target/backend-package/rei-backend-panes.jar' }
if (-not (Test-Path -LiteralPath $BackendJar)) { throw 'Build the Backend JAR first or supply -BackendJar' }
$BackendJar=(Resolve-Path -LiteralPath $BackendJar).Path
function Complete-GuiFixture {
  $fixture=Get-Content -LiteralPath (Join-Path $CaseRoot 'gui-fixture.json') -Raw | ConvertFrom-Json
  $endpointPath=Join-Path $CaseRoot 'data/.storage/backend-endpoint.json'
  $owned=Get-Content -LiteralPath $endpointPath -Raw | ConvertFrom-Json
  $headers=@{Authorization='Bearer fixture-secret'}
  & powershell.exe -NoProfile -File (Join-Path $PSScriptRoot 'Close-WindowsTerminalFixture.ps1') -CaseRoot $CaseRoot
  if ($LASTEXITCODE -ne 0) { throw 'Dedicated GUI fixture close failed' }
  $identity=Invoke-RestMethod -Uri ($owned.baseUrl+'/api/v1/instance') -Headers $headers
  if ($identity.instanceId -ne $owned.instanceId) { throw 'Backend identity changed after GUI close' }
  foreach ($hostPid in @($fixture.firstPanePid,$fixture.secondPanePid)) {
    if (Get-Process -Id $hostPid -ErrorAction SilentlyContinue) { throw 'Pane host survived GUI window close' }
  }
  $running=Invoke-RestMethod -Uri ($owned.baseUrl+'/api/v1/runs/'+$fixture.runId) -Headers $headers
  if ($running.status -ne 'RUNNING') { throw ('Run was not running after GUI close: '+$running.status) }
  Set-Content -LiteralPath (Join-Path $CaseRoot 'release-provider') -Value 'GUI close verified'
  $deadline=[DateTime]::UtcNow.AddSeconds(30)
  do {
    $completed=Invoke-RestMethod -Uri ($owned.baseUrl+'/api/v1/runs/'+$fixture.runId) -Headers $headers
    if ($completed.status -eq 'COMPLETED') { break }
    if ($completed.status -in @('FAILED','CANCELLED','UNKNOWN')) { throw ('Run failed after GUI close: '+$completed.status) }
    Start-Sleep -Milliseconds 100
  } while ([DateTime]::UtcNow -lt $deadline)
  if ($completed.status -ne 'COMPLETED') { throw 'Run did not complete after GUI close' }
  $info=[System.Diagnostics.ProcessStartInfo]::new()
  $info.FileName=if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { (Get-Command java.exe).Source }
  $info.WorkingDirectory=$repoRoot;$info.UseShellExecute=$false;$info.CreateNoWindow=$true
  $info.RedirectStandardInput=$true;$info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true
  foreach ($argument in @('--enable-native-access=ALL-UNNAMED',('-Duser.home='+ (Join-Path $CaseRoot 'Reconnect')),'-jar',(Join-Path $repoRoot 'terminal/launcher/target/rei-launcher-0.0.1-SNAPSHOT.jar'),'--mode=client','--no-history','--project',$CaseRoot)) { $info.ArgumentList.Add($argument) }
  $info.Environment['REI_DATA_DIR']=Join-Path $CaseRoot 'data';$info.Environment['REI_API_KEY']='fixture-secret'
  $reconnected=[System.Diagnostics.Process]::Start($info)
  try {
    $stdout=$reconnected.StandardOutput.ReadToEndAsync();$stderr=$reconnected.StandardError.ReadToEndAsync()
    foreach ($command in @(('/session select '+$fixture.sessionId),'/history show',('/runs '+$fixture.runId),'/models','/session new after-gui-close','/exit')) { $reconnected.StandardInput.WriteLine($command) }
    $reconnected.StandardInput.Close()
    if (-not $reconnected.WaitForExit(20000) -or $reconnected.ExitCode -ne 0) { throw 'CLI reconnect after GUI close failed' }
    $output=$stdout.GetAwaiter().GetResult()+$stderr.GetAwaiter().GetResult();Set-Content -LiteralPath (Join-Path $CaseRoot 'gui-reconnect.log') -Value $output
    if ($output -notmatch 'Pane run completed' -or $output -notmatch 'COMPLETED' -or $output -match 'Backend rejected request|Unsupported CLI command|Backend connection failed') { throw 'Reconnected CLI did not retrieve the completed Run and saved history' }
  } finally { if (-not $reconnected.HasExited) { $reconnected.Kill();$reconnected.WaitForExit() } }
  $sessions=Invoke-RestMethod -Uri ($owned.baseUrl+'/api/v1/sessions?projectId='+$fixture.projectId) -Headers $headers
  if ($sessions.items.Count -lt 2) { throw 'Reconnected CLI did not create a Session' }
  Invoke-RestMethod -Method Post -Uri ($owned.baseUrl+'/api/v1/instance/stop') -Headers $headers -ContentType 'application/json' -Body (@{instanceId=$owned.instanceId;storageId=$owned.storageId}|ConvertTo-Json) | Out-Null
  $deadline=[DateTime]::UtcNow.AddSeconds(30)
  while ((Test-Path -LiteralPath $endpointPath) -and [DateTime]::UtcNow -lt $deadline) { Start-Sleep -Milliseconds 100 }
  if (Test-Path -LiteralPath $endpointPath) { throw 'Endpoint survived explicit stop after GUI close' }
  @{result='PASS';caseRoot=$CaseRoot;guiClose=$true;guiAction='UI Automation WindowPattern.Close';closeAllConfirmation=(Test-Path -LiteralPath (Join-Path $CaseRoot 'gui-close-confirmation.json'));firstPanePid=$fixture.firstPanePid;secondPanePid=$fixture.secondPanePid;instanceId=$owned.instanceId;backendPid=$owned.pid;runId=$fixture.runId;runAfterGuiClose=$running.status;runFinalStatus=$completed.status;reconnectedCli='Retrieved Run/history and created a Session';explicitStop='Endpoint removed'} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $CaseRoot 'result.json')
}
if ($Role -eq 'Coordinator') {
  $CaseRoot=Join-Path $repoRoot ('target/windows-terminal-e2e-'+[guid]::NewGuid())
  New-Item -ItemType Directory -Path $CaseRoot | Out-Null
  if ($GuiClose) { Set-Content -LiteralPath (Join-Path $CaseRoot 'gui-close-requested') -Value 'true' }
  $pwsh=(Get-Command pwsh.exe).Source
  $wt=(Get-Command wt.exe).Source
  $providerInfo=[System.Diagnostics.ProcessStartInfo]::new()
  $providerInfo.FileName=(Get-Command python.exe).Source
  $providerInfo.UseShellExecute=$false;$providerInfo.CreateNoWindow=$true
  $providerInfo.ArgumentList.Add((Join-Path $PSScriptRoot 'windows_terminal_provider_fixture.py'))
  $providerInfo.ArgumentList.Add($CaseRoot)
  $provider=[System.Diagnostics.Process]::Start($providerInfo)
  try {
  $providerDeadline=[DateTime]::UtcNow.AddSeconds(10)
  while (-not (Test-Path -LiteralPath (Join-Path $CaseRoot 'provider-url'))) {
    if ($provider.HasExited -or [DateTime]::UtcNow -gt $providerDeadline) { throw 'Loopback provider fixture failed to start' }
    Start-Sleep -Milliseconds 100
  }
  # Use a fresh window, leaving the user's existing tabs and panes untouched.
  $suffix=if ($GuiClose) { ' '+(Split-Path -Leaf $CaseRoot) } else { '' }
  & $wt -w new new-tab --title ('Rei E2E first'+$suffix) $pwsh -NoProfile -File $PSCommandPath -Role First -CaseRoot $CaseRoot -BackendJar $BackendJar ';' split-pane -V --title ('Rei E2E second'+$suffix) $pwsh -NoProfile -File $PSCommandPath -Role Second -CaseRoot $CaseRoot -BackendJar $BackendJar
  Write-Output ("E2E case: "+$CaseRoot)
  $deadline=[DateTime]::UtcNow.AddSeconds(240)
  while ([DateTime]::UtcNow -lt $deadline) {
    if ($GuiClose -and (Test-Path -LiteralPath (Join-Path $CaseRoot 'gui-close-ready')) -and (Test-Path -LiteralPath (Join-Path $CaseRoot 'gui-fixture.json')) -and -not (Test-Path -LiteralPath (Join-Path $CaseRoot 'result.json'))) { Complete-GuiFixture }
    foreach ($name in @('First.error','Second.error')) {
      $errorPath=Join-Path $CaseRoot $name
      if (Test-Path -LiteralPath $errorPath) { throw (Get-Content -LiteralPath $errorPath -Raw) }
    }
    $resultPath=Join-Path $CaseRoot 'result.json'
    if (Test-Path -LiteralPath $resultPath) { Get-Content -LiteralPath $resultPath -Raw; exit 0 }
    Start-Sleep -Milliseconds 250
  }
  throw "Windows Terminal panes timed out; evidence: $CaseRoot"
  } finally {
    Set-Content -LiteralPath (Join-Path $CaseRoot 'second-ready') -Value 'coordinator-finished'
    Set-Content -LiteralPath (Join-Path $CaseRoot 'release-provider') -Value 'coordinator-finished'
    $fixtureEndpoint=Join-Path $CaseRoot 'data/.storage/backend-endpoint.json'
    if (Test-Path -LiteralPath $fixtureEndpoint) {
      $owned=Get-Content -LiteralPath $fixtureEndpoint -Raw | ConvertFrom-Json
      try { Invoke-RestMethod -Method Post -Uri ($owned.baseUrl+'/api/v1/instance/stop') -Headers @{Authorization='Bearer fixture-secret'} -ContentType 'application/json' -Body (@{instanceId=$owned.instanceId;storageId=$owned.storageId}|ConvertTo-Json) | Out-Null } catch { Write-Warning 'Fixture Backend cleanup failed' }
    }
    if (-not $provider.HasExited) { $provider.Kill();$provider.WaitForExit() }
  }
}
$GuiClose=Test-Path -LiteralPath (Join-Path $CaseRoot 'gui-close-requested')
function Wait-File([string]$Name) {
  $path=Join-Path $CaseRoot $Name
  $deadline=[DateTime]::UtcNow.AddSeconds(110)
  while (-not (Test-Path -LiteralPath $path)) {
    foreach ($peer in @('First.error','Second.error')) {
      if (Test-Path -LiteralPath (Join-Path $CaseRoot $peer)) { throw "Peer pane failed: $peer" }
    }
    if ([DateTime]::UtcNow -gt $deadline) { throw "Timed out waiting for $Name" }
    Start-Sleep -Milliseconds 100
  }
  return $path
}
function Start-Client([string[]]$Arguments) {
  $info=[System.Diagnostics.ProcessStartInfo]::new()
  $info.FileName=if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { (Get-Command java.exe).Source }
  $info.WorkingDirectory=$repoRoot
  $info.UseShellExecute=$false
  $info.RedirectStandardInput=$true
  $info.RedirectStandardOutput=$true
  $info.RedirectStandardError=$true
  foreach ($argument in @('--enable-native-access=ALL-UNNAMED',('-Duser.home='+ (Join-Path $CaseRoot $Role)),'-jar',(Join-Path $repoRoot 'terminal/launcher/target/rei-launcher-0.0.1-SNAPSHOT.jar'),'--backend-jar',$BackendJar,'--no-history')+$Arguments) { $info.ArgumentList.Add($argument) }
  $info.Environment['REI_DATA_DIR']=Join-Path $CaseRoot 'data'
  $info.Environment['REI_API_KEY']='fixture-secret'
  $info.Environment['REI_OPENAI_BASE_URL']=(Get-Content -LiteralPath (Join-Path $CaseRoot 'provider-url') -Raw).Trim()
  $info.Environment['REI_OPENAI_API_KEY']='fixture-provider'
  $info.Environment['REI_OPENAI_CHAT_MODEL']='fixture'
  foreach ($name in @('REI_EMBEDDING_ENABLED','REI_RERANK_ENABLED','REI_GOOGLE_TASK_ENABLED','REI_GOOGLE_CALENDAR_ENABLED','REI_PAPER_ENABLED','REI_TOPIC_GENERATOR_ENABLED','REI_INTEREST_ENABLED','REI_BLUESKY_ENABLED','REI_MEMORY_AUTO_SLEEP_ENABLED','REI_MCP_ENABLED')) { $info.Environment[$name]='false' }
  return [System.Diagnostics.Process]::Start($info)
}
$client=$null
$endpointPath=Join-Path $CaseRoot 'data/.storage/backend-endpoint.json'
$headers=@{Authorization='Bearer fixture-secret'}
try {
  if (-not $env:WT_SESSION -or [Console]::IsInputRedirected) { throw 'Worker is not hosted in an interactive Windows Terminal pane' }
  @{role=$Role;panePid=$PID;wtSession=$env:WT_SESSION;wtProfile=$env:WT_PROFILE_ID;inputRedirected=[Console]::IsInputRedirected} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $CaseRoot ($Role+'.json'))
  # Both panes enter auto mode simultaneously, exercising the real startup race.
  $client=Start-Client @('--mode=auto')
  $stdout=$client.StandardOutput.ReadToEndAsync();$stderr=$client.StandardError.ReadToEndAsync()
  if ($Role -eq 'First') {
    Wait-File 'data/.storage/backend-endpoint.json' | Out-Null
    $endpoint=Get-Content -LiteralPath $endpointPath -Raw | ConvertFrom-Json
    $deadline=[DateTime]::UtcNow.AddSeconds(30)
    do {
      try { $identity=Invoke-RestMethod -Uri ($endpoint.baseUrl+'/api/v1/instance') -Headers $headers; break } catch { Start-Sleep -Milliseconds 100 }
    } while ([DateTime]::UtcNow -lt $deadline)
    if ($identity.instanceId -ne $endpoint.instanceId) { throw 'First pane failed readiness verification' }
    Set-Content -LiteralPath (Join-Path $CaseRoot 'ready') -Value $endpoint.instanceId
    Wait-File 'second-ready' | Out-Null
    if ($GuiClose) {
      Set-Content -LiteralPath (Join-Path $CaseRoot 'gui-close-ready') -Value 'Close only the first pane through Windows Terminal UI; do not send /exit'
      Write-Host 'Rei E2E first: GUI force close this pane now. CLI is still running.'
      Wait-File 'gui-close-timeout' | Out-Null
      throw 'GUI close was not performed'
    }
    $client.StandardInput.WriteLine('/exit');$client.StandardInput.Close()
    if (-not $client.WaitForExit(10000) -or $client.ExitCode -ne 0) { throw 'First CLI did not exit cleanly' }
    Set-Content -LiteralPath (Join-Path $CaseRoot 'first-client.log') -Value ($stdout.GetAwaiter().GetResult()+$stderr.GetAwaiter().GetResult())
    # Exiting the host process closes this pane under the normal closeOnExit setting.
    exit 0
  }
  Wait-File 'ready' | Out-Null
  $initial=Get-Content -LiteralPath $endpointPath -Raw | ConvertFrom-Json
  if ((Get-Content -LiteralPath (Join-Path $CaseRoot 'ready') -Raw).Trim() -ne $initial.instanceId) { throw 'Simultaneous auto clients reached different Backend instances' }
  $first=Get-Content -LiteralPath (Wait-File 'First.json') -Raw | ConvertFrom-Json
  # WT_SESSION identifies each pane's terminal connection, not the containing window.
  if ($first.wtSession -eq $env:WT_SESSION -or $first.panePid -eq $PID -or $first.inputRedirected) { throw 'Workers are not distinct interactive Terminal panes' }
  $client.StandardInput.WriteLine('/project register "'+$CaseRoot+'"')
  $client.StandardInput.WriteLine('/session new pane-fixture')
  $client.StandardInput.Flush()
  $deadline=[DateTime]::UtcNow.AddSeconds(20)
  do {
    $projects=Invoke-RestMethod -Uri ($initial.baseUrl+'/api/v1/projects') -Headers $headers
    if ($projects.Count -eq 1) { $sessions=Invoke-RestMethod -Uri ($initial.baseUrl+'/api/v1/sessions?projectId='+$projects[0].id) -Headers $headers }
    if ($sessions.items.Count -eq 1) { break }
    Start-Sleep -Milliseconds 100
  } while ([DateTime]::UtcNow -lt $deadline)
  if ($sessions.items.Count -ne 1 -or $client.HasExited) { throw 'Second CLI failed Project/Session operations' }
  $run=Invoke-RestMethod -Method Post -Uri ($initial.baseUrl+'/api/v1/chat') -Headers ($headers+@{'Idempotency-Key'='pane-run'}) -ContentType 'application/json' -Body (@{projectId=$projects[0].id;sessionId=$sessions.items[0].sessionId;message='Complete the pane fixture';mode='CONVERSATION'}|ConvertTo-Json)
  Wait-File 'provider-blocked' | Out-Null
  $client.StandardInput.WriteLine('/watch '+$run.runId);$client.StandardInput.Flush()
  Set-Content -LiteralPath (Join-Path $CaseRoot 'second-ready') -Value $client.Id
  if ($GuiClose) {
    @{firstPanePid=$first.panePid;secondPanePid=$PID;projectId=$projects[0].id;sessionId=$run.sessionId;runId=$run.runId} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $CaseRoot 'gui-fixture.json')
    Wait-File 'gui-close-timeout' | Out-Null
    throw 'GUI close was not performed'
  }
  $deadline=[DateTime]::UtcNow.AddSeconds($(if ($GuiClose) { 90 } else { 20 }))
  while (Get-Process -Id $first.panePid -ErrorAction SilentlyContinue) {
    if ([DateTime]::UtcNow -gt $deadline) { throw 'First pane host did not exit' }
    Start-Sleep -Milliseconds 100
  }
  $identity=Invoke-RestMethod -Uri ($initial.baseUrl+'/api/v1/instance') -Headers $headers
  if ($identity.instanceId -ne $initial.instanceId -or $client.HasExited) { throw 'Backend or second CLI died after first pane exited' }
  $running=Invoke-RestMethod -Uri ($initial.baseUrl+'/api/v1/runs/'+$run.runId) -Headers $headers
  if ($running.status -ne 'RUNNING') { throw ('Run did not remain running after first pane exit: '+$running.status) }
  Set-Content -LiteralPath (Join-Path $CaseRoot 'release-provider') -Value 'complete'
  $deadline=[DateTime]::UtcNow.AddSeconds(30)
  do {
    $completed=Invoke-RestMethod -Uri ($initial.baseUrl+'/api/v1/runs/'+$run.runId) -Headers $headers
    if ($completed.status -eq 'COMPLETED') { break }
    if ($completed.status -in @('FAILED','CANCELLED','UNKNOWN')) { throw ('Run failed after pane exit: '+$completed.status) }
    Start-Sleep -Milliseconds 100
  } while ([DateTime]::UtcNow -lt $deadline)
  if ($completed.status -ne 'COMPLETED') { throw 'Run did not complete after pane exit' }
  $client.StandardInput.WriteLine('/models');$client.StandardInput.WriteLine('/model fixture');$client.StandardInput.Flush()
  $goalFile=Join-Path $CaseRoot 'pane-result.txt'
  Set-Content -LiteralPath $goalFile -Value 'fixture result'
  $goalDigest=(Get-FileHash -LiteralPath $goalFile -Algorithm SHA256).Hash.ToLowerInvariant()
  $client.StandardInput.WriteLine('/goal create "pane goal" --file pane-result.txt --sha256 '+$goalDigest);$client.StandardInput.Flush()
  $client.StandardInput.WriteLine('/session new after-first-pane-exit');$client.StandardInput.Flush()
  $deadline=[DateTime]::UtcNow.AddSeconds(10)
  do {
    $sessions=Invoke-RestMethod -Uri ($initial.baseUrl+'/api/v1/sessions?projectId='+$projects[0].id) -Headers $headers
    if ($sessions.items.Count -eq 2) { break }
    Start-Sleep -Milliseconds 100
  } while ([DateTime]::UtcNow -lt $deadline)
  if ($sessions.items.Count -ne 2) { throw 'Second CLI did not operate after first pane exit' }
  $client.StandardInput.WriteLine('/exit');$client.StandardInput.Close()
  if (-not $client.WaitForExit(10000) -or $client.ExitCode -ne 0) { throw 'Second CLI did not exit cleanly' }
  Set-Content -LiteralPath (Join-Path $CaseRoot 'second-client.log') -Value ($stdout.GetAwaiter().GetResult()+$stderr.GetAwaiter().GetResult())
  if ((Get-Content -LiteralPath (Join-Path $CaseRoot 'second-client.log') -Raw) -notmatch 'Pane run completed') { throw 'Second CLI did not receive the Run SSE output' }
  $models=Invoke-RestMethod -Uri ($initial.baseUrl+'/api/v1/models') -Headers $headers
  if ($models.currentModel -ne 'fixture' -or $models.models.Count -ne 1) { throw 'Named model API failed' }
  $goals=Invoke-RestMethod -Uri ($initial.baseUrl+'/api/v1/projects/'+$projects[0].id+'/goals') -Headers $headers
  if ($goals.Count -ne 1 -or $goals[0].objective -ne 'pane goal' -or $goals[0].sessionId -ne $run.sessionId) { throw 'CLI Goal creation lost captured ownership' }
  if ((Get-Content -LiteralPath (Join-Path $CaseRoot 'second-client.log') -Raw) -match 'Invalid API mutation|Unsupported CLI command|Backend rejected request') { throw 'CLI reported an API or command error' }
  $stop=Start-Client @('server','stop','--yes')
  $stop.StandardInput.Close();$stopOut=$stop.StandardOutput.ReadToEndAsync();$stopErr=$stop.StandardError.ReadToEndAsync()
  if (-not $stop.WaitForExit(35000) -or $stop.ExitCode -ne 0) { throw 'Explicit stop failed' }
  Set-Content -LiteralPath (Join-Path $CaseRoot 'stop.log') -Value ($stopOut.GetAwaiter().GetResult()+$stopErr.GetAwaiter().GetResult())
  if (Test-Path -LiteralPath $endpointPath) { throw 'Endpoint survived explicit stop' }
  @{result='PASS';caseRoot=$CaseRoot;guiClose=[bool]$GuiClose;wtSession=$env:WT_SESSION;firstPanePid=$first.panePid;secondPanePid=$PID;instanceId=$initial.instanceId;backendPid=$initial.pid;runId=$run.runId;runAfterFirstPaneExit=$running.status;runFinalStatus=$completed.status;runSse='Second CLI received completed response';firstPaneExit='Same Backend and second CLI remained alive';secondClientAfterExit='Created another Session';explicitStop='Endpoint removed';limitations='CLI stdin/stdout are redirected inside real Terminal pane hosts; interactive PTY is not covered'} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $CaseRoot 'result.json')
} catch {
  $_ | Out-String | Set-Content -LiteralPath (Join-Path $CaseRoot ($Role+'.error'))
  exit 1
} finally {
  if ($client -and -not $client.HasExited) { $client.Kill() }
  if ($Role -eq 'Second' -and -not $GuiClose -and (Test-Path -LiteralPath $endpointPath)) {
    $owned=Get-Content -LiteralPath $endpointPath -Raw | ConvertFrom-Json
    try { Invoke-RestMethod -Method Post -Uri ($owned.baseUrl+'/api/v1/instance/stop') -Headers $headers -ContentType 'application/json' -Body (@{instanceId=$owned.instanceId;storageId=$owned.storageId}|ConvertTo-Json) | Out-Null } catch { Write-Warning 'Fixture Backend cleanup failed' }
  }
}
