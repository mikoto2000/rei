param([ValidateRange(1,600)][int]$TimeoutSeconds=300)
$ErrorActionPreference='Stop'
if (-not $IsWindows) { throw 'Windows process I/O measurement is required' }
$repository=Split-Path $PSScriptRoot -Parent
$artifacts=Join-Path $repository 'target'
New-Item -ItemType Directory -Force -Path $artifacts | Out-Null
Add-Type @"
using System;
using System.Runtime.InteropServices;
public static class ReviewProcessIo {
 [StructLayout(LayoutKind.Sequential)] public struct Counters { public ulong Reads,Writes,Other,ReadBytes,WriteBytes,OtherBytes; }
 [DllImport("kernel32.dll",SetLastError=true)] static extern IntPtr OpenProcess(uint access,bool inherit,int id);
 [DllImport("kernel32.dll",SetLastError=true)] static extern bool GetProcessIoCounters(IntPtr handle,out Counters counters);
 [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr handle);
 public static Counters Read(int id) { var h=OpenProcess(0x1000,false,id);try { Counters c; if(h!=IntPtr.Zero&&GetProcessIoCounters(h,out c))return c;return new Counters(); } finally {if(h!=IntPtr.Zero)CloseHandle(h);} }
}
"@
$command='"'+(Join-Path $repository 'mvnw.cmd')+'" -o "-Dtest=ReviewQualityEvaluationTest,RawPagingScaleTest" test -q'
$process=Start-Process -FilePath $env:ComSpec -ArgumentList ('/d /s /c "'+$command+'"') -WorkingDirectory $repository -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $artifacts 'review-process.stdout.log') -RedirectStandardError (Join-Path $artifacts 'review-process.stderr.log')
$owned=@{};$owned[$process.Id]=$true;$measurements=@{};$peak=0L
$clock=[Diagnostics.Stopwatch]::StartNew()
try {
 do {
  $inventory=Get-CimInstance Win32_Process | Select-Object ProcessId,ParentProcessId,CreationDate
  do { $changed=$false;foreach($entry in $inventory){if($owned.ContainsKey([int]$entry.ParentProcessId)-and -not $owned.ContainsKey([int]$entry.ProcessId)){$owned[[int]$entry.ProcessId]=$true;$changed=$true}} }while($changed)
  $working=0L
  foreach($entry in $inventory){if(-not $owned.ContainsKey([int]$entry.ProcessId)){continue}
   $identity=[string]$entry.ProcessId+':'+[string]$entry.CreationDate
   $counter=[ReviewProcessIo]::Read([int]$entry.ProcessId)
   $previous=$measurements[$identity]
   $measurements[$identity]=@{readBytes=[Math]::Max([ulong]$previous.readBytes,$counter.ReadBytes);writeBytes=[Math]::Max([ulong]$previous.writeBytes,$counter.WriteBytes);readOperations=[Math]::Max([ulong]$previous.readOperations,$counter.Reads);writeOperations=[Math]::Max([ulong]$previous.writeOperations,$counter.Writes)}
   try{$working+=(Get-Process -Id $entry.ProcessId -ErrorAction Stop).WorkingSet64}catch{}
  }
  $peak=[Math]::Max($peak,$working)
  if($clock.Elapsed.TotalSeconds -ge $TimeoutSeconds){$process.Kill($true);throw 'Owned evaluation process exceeded deadline'}
 }while(-not $process.WaitForExit(1000))
 $process.Refresh();if($null-eq $process.ExitCode){throw 'Evaluation exit code unavailable'}
 $report=@{exitCode=$process.ExitCode;elapsedMillis=$clock.ElapsedMilliseconds;observedPeakWorkingSetBytes=$peak;observedProcessReadBytes=0UL;observedProcessWriteBytes=0UL;observedProcessReadOperations=0UL;observedProcessWriteOperations=0UL;notes='Sampled process-tree I/O includes Maven compilation, SQLite native I/O, files and pipes. Last sample may miss exit I/O; it is an observed lower bound, not physical disk attribution.'}
 foreach($value in $measurements.Values){$report.observedProcessReadBytes+=$value.readBytes;$report.observedProcessWriteBytes+=$value.writeBytes;$report.observedProcessReadOperations+=$value.readOperations;$report.observedProcessWriteOperations+=$value.writeOperations}
 $report | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $artifacts 'review-process-io.json') -Encoding utf8
 if($process.ExitCode-ne 0){throw ('Evaluation failed; exitCode='+$process.ExitCode)}
 Get-Content -LiteralPath (Join-Path $artifacts 'review-process-io.json')
}finally{if(-not $process.HasExited){$process.Kill($true)}}
