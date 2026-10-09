param([string]$Profile,[string]$Models,[string]$Fixtures,[int]$Threads=1,[int]$Tail=1000,[int]$Silence=1200,[string]$Runtime="target/voice-phase5-turbo",[ValidatePattern("^[A-Za-z0-9_-]+$")][string]$Label="main")
$ErrorActionPreference='Stop'
$taskRoot=(Get-Location).Path
$taskOut=Join-Path $taskRoot ('target/benchmark-'+$Label+'-'+$Profile+'-t'+$Threads+'-p'+$Tail+'-s'+$Silence)
$taskJar=(Resolve-Path -LiteralPath $Runtime).Path
$taskCp='target/classes;target/voice-benchmark-classes;'+$taskJar+'/jvm.jar;'+$taskJar+'/native.jar'
$taskArgs=@('--enable-native-access=ALL-UNNAMED','-XX:-CreateCoredumpOnCrash','-XX:ErrorFile=NUL','-Dstdout.encoding=UTF-8','-cp',$taskCp,'dev.mikoto2000.rei.voice.VoiceBenchmark',$Models,$Profile,$Fixtures,($taskOut+'.tsv'),$Threads,$Tail,$Silence)
$taskArgsFile=$taskOut+'.java.args'
$taskQuotedArgs=@($taskArgs | ForEach-Object {'"'+([string]$_).Replace('\','\\').Replace('"','\"')+'"'})
[IO.File]::WriteAllText($taskArgsFile,($taskQuotedArgs -join [Environment]::NewLine),[Text.UTF8Encoding]::new($false))
$taskProcess=Start-Process -FilePath (Get-Command java).Source -ArgumentList ('"@'+$taskArgsFile+'"') -WindowStyle Hidden -PassThru -RedirectStandardOutput ($taskOut+'.stdout.log') -RedirectStandardError ($taskOut+'.stderr.log')
$taskPeak=0L;$taskDeadline=[DateTime]::UtcNow.AddMinutes(30)
try {
  while(-not $taskProcess.HasExited){
    $taskProcess.Refresh();$taskPeak=[Math]::Max($taskPeak,$taskProcess.WorkingSet64)
    if([DateTime]::UtcNow -gt $taskDeadline){$taskProcess.Kill();throw 'Owned benchmark exceeded 30 minutes'}
    Start-Sleep -Milliseconds 100
  }
  $taskProcess.WaitForExit()
  [pscustomobject]@{profile=$Profile;threads=$Threads;tail=$Tail;silenceMs=$Silence;pid=$taskProcess.Id;maxWorkingSetBytes=$taskPeak;cpuSeconds=$taskProcess.TotalProcessorTime.TotalSeconds;exitCode=$taskProcess.ExitCode;samplingMs=100} | ConvertTo-Json | Set-Content -Encoding utf8 ($taskOut+'.process.json')
  Get-Content ($taskOut+'.stdout.log') -Tail 6
  if($taskProcess.ExitCode -ne 0){Get-Content ($taskOut+'.stderr.log') -Tail 10;throw 'Benchmark child failed'}
}finally{if(-not $taskProcess.HasExited){$taskProcess.Kill()};$taskProcess.Dispose()}