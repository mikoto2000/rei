param([string]$BackendJar)
$ErrorActionPreference = 'Stop'
if (-not $IsWindows) { throw 'This E2E requires Windows and JDK 25' }
$repoRoot = Split-Path -Parent $PSScriptRoot
$caseRoot = Join-Path $repoRoot ('target/windows-cli-e2e-' + [guid]::NewGuid().ToString())
New-Item -ItemType Directory -Path $caseRoot | Out-Null
$launcher = Join-Path $repoRoot 'terminal/launcher/target/rei-launcher-0.0.1-SNAPSHOT.jar'
$backend = if ($BackendJar) { (Resolve-Path -LiteralPath $BackendJar).Path } else { Join-Path $repoRoot 'target/backend-package/rei-backend.jar' }
$javaExecutable = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { (Get-Command java.exe).Source }
if (-not (Test-Path -LiteralPath $javaExecutable)) { $javaExecutable = (Get-Command java.exe).Source }
if (-not (Test-Path -LiteralPath $launcher) -or -not (Test-Path -LiteralPath $backend)) { throw 'Build the launcher and Backend JAR before running this E2E' }
# This isolated fixture key is never copied into a production configuration or discovery file.
$fixtureKey = 'fixture-secret'
function Invoke-Client([string[]]$clientArguments, [string]$inputText, [string]$label) {
  $info = [System.Diagnostics.ProcessStartInfo]::new()
  $info.FileName = $javaExecutable
  $info.WorkingDirectory = $repoRoot
  $info.UseShellExecute = $false
  $info.CreateNoWindow = $true
  $info.RedirectStandardInput = $true
  $info.RedirectStandardOutput = $true
  $info.RedirectStandardError = $true
  foreach ($argument in @('--enable-native-access=ALL-UNNAMED', ('-Duser.home=' + (Join-Path $caseRoot $label)), '-jar', $launcher, '--backend-jar', $backend, '--no-history') + $clientArguments) { $info.ArgumentList.Add($argument) }
  $info.Environment['REI_DATA_DIR'] = Join-Path $caseRoot 'data'
  $info.Environment['REI_API_KEY'] = $fixtureKey
  $info.Environment['REI_OPENAI_BASE_URL'] = 'http://127.0.0.1:1'
  foreach ($name in @('REI_EMBEDDING_ENABLED','REI_RERANK_ENABLED','REI_GOOGLE_TASK_ENABLED','REI_GOOGLE_CALENDAR_ENABLED','REI_PAPER_ENABLED','REI_TOPIC_GENERATOR_ENABLED','REI_INTEREST_ENABLED','REI_BLUESKY_ENABLED','REI_MEMORY_AUTO_SLEEP_ENABLED','REI_MCP_ENABLED')) { $info.Environment[$name] = 'false' }
  $process = [System.Diagnostics.Process]::Start($info)
  $stdout = $process.StandardOutput.ReadToEndAsync()
  $stderr = $process.StandardError.ReadToEndAsync()
  $process.StandardInput.Write($inputText)
  $process.StandardInput.Close()
  if (-not $process.WaitForExit(110000)) { $process.Kill(); throw "$label timed out" }
  $text = $stdout.GetAwaiter().GetResult() + $stderr.GetAwaiter().GetResult()
  Set-Content -LiteralPath (Join-Path $caseRoot ($label + '.log')) -Value $text -Encoding utf8
  if ($process.ExitCode -ne 0) { throw "$label failed: $text" }
  return $text
}
$endpointPath = Join-Path $caseRoot 'data/.storage/backend-endpoint.json'
try {
  Invoke-Client @('--mode=auto') "/exit`n" 'first' | Out-Null
  $initial = Get-Content -LiteralPath $endpointPath -Raw | ConvertFrom-Json
  if (-not (Get-Process -Id $initial.pid -ErrorAction SilentlyContinue)) { throw 'Backend died with first client' }
  $headers = @{ Authorization = ('Bearer ' + $fixtureKey) }
  $identity = Invoke-RestMethod -Uri ($initial.baseUrl + '/api/v1/instance') -Headers $headers
  if ($identity.instanceId -ne $initial.instanceId) { throw 'Identity mismatch' }
  Invoke-Client @('--mode=client') "/project register `"$caseRoot`"`n/session new fixture`n/mode conversation`n/exit`n" 'second' | Out-Null
  $after = Get-Content -LiteralPath $endpointPath -Raw | ConvertFrom-Json
  if ($initial.instanceId -ne $after.instanceId) { throw 'Second client replaced Backend' }
  $projects = Invoke-RestMethod -Uri ($initial.baseUrl + '/api/v1/projects') -Headers $headers
  if ($projects.Count -ne 1) { throw 'Project registration failed' }
  $saved = Invoke-RestMethod -Uri ($initial.baseUrl + '/api/v1/sessions?projectId=' + $projects[0].id) -Headers $headers
  if ($saved.items.Count -ne 1) { throw 'Empty Session creation failed' }
  $sessionId = $saved.items[0].sessionId
  $style = Invoke-RestMethod -Uri ($initial.baseUrl + '/api/v1/sessions/' + $sessionId + '/response-style?projectId=' + $projects[0].id) -Headers $headers
  if ($style.style -ne 'CONVERSATION') { throw 'Response style operation failed' }
  # The provider is deliberately unreachable loopback: acceptance/recovery is tested without an external LLM.
  $http = [System.Net.Http.HttpClient]::new()
  try {
    $http.Timeout = [TimeSpan]::FromSeconds(20)
    $requests = @()
    for ($attempt = 0; $attempt -lt 2; $attempt++) {
      $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post, ($initial.baseUrl + '/api/v1/chat'))
      $request.Headers.Add('Authorization', ('Bearer ' + $fixtureKey))
      $request.Headers.Add('Idempotency-Key', 'windows-e2e-receipt')
      $request.Content = [System.Net.Http.StringContent]::new((@{projectId=$projects[0].id;sessionId=$sessionId;message='fixture acceptance';mode='CONVERSATION'} | ConvertTo-Json), [System.Text.Encoding]::UTF8, 'application/json')
      $requests += $http.SendAsync($request)
    }
    $responses = @($requests | ForEach-Object { $_.GetAwaiter().GetResult() })
    if (@($responses | Where-Object { [int]$_.StatusCode -ne 202 }).Count) { throw 'Concurrent receipt submission was rejected' }
    $firstRun = $responses[0].Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json
    $secondRun = $responses[1].Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json
    if ($firstRun.runId -ne $secondRun.runId) { throw 'Receipt double dispatch' }
  } finally { $http.Dispose() }
  Invoke-Client @('server','status') '' 'status' | Out-Null
  Invoke-Client @('server','stop','--yes') '' 'stop' | Out-Null
  if (Test-Path -LiteralPath $endpointPath) { throw 'Endpoint remained after stop' }
  Invoke-Client @('--mode=server') '' 'restart' | Out-Null
  $restarted = Get-Content -LiteralPath $endpointPath -Raw | ConvertFrom-Json
  $receipt = Invoke-RestMethod -Uri ($restarted.baseUrl + '/api/v1/chat/receipts/windows-e2e-receipt') -Headers $headers
  if ($receipt.runId -ne $firstRun.runId) { throw 'Receipt lost across Backend restart' }
  Invoke-Client @('server','stop','--yes') '' 'restart-stop' | Out-Null
  [pscustomobject]@{result='PASS'; caseRoot=$caseRoot; instanceId=$initial.instanceId; pid=$initial.pid; firstClientExit='Backend remained ready'; secondClient='Same instance, Project/Session/style operations succeeded'; concurrentReceipt='Same Run ID'; restartReceipt=$receipt.status; explicitStop='Endpoint removed'} | ConvertTo-Json
} finally {
  if (Test-Path -LiteralPath $endpointPath) {
    $owned = Get-Content -LiteralPath $endpointPath -Raw | ConvertFrom-Json
    try { Invoke-RestMethod -Method Post -Uri ($owned.baseUrl + '/api/v1/instance/stop') -Headers @{Authorization=('Bearer ' + $fixtureKey)} -ContentType 'application/json' -Body (@{instanceId=$owned.instanceId;storageId=$owned.storageId}|ConvertTo-Json) | Out-Null } catch { Write-Warning 'Fixture Backend could not be stopped through its authenticated identity API' }
  }
}
