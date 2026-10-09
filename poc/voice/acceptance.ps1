param(
 [Parameter(Mandatory=$true)][string]$Bundle,
 [Parameter(Mandatory=$true)][string]$DataDirectory,
 [Parameter(Mandatory=$true)][string]$ReadyFile,
 [string]$Microphone='DRY (VT-4)',
 [Parameter(Mandatory=$true)][string]$ExternalConfig
)
$ErrorActionPreference='Stop'
$root=Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$jar=Join-Path $root 'target/rei-0.0.1-SNAPSHOT.jar'
$runtime=Join-Path $root 'target/voice-acceptance-runtime'
$classes=Join-Path $runtime 'classes'
$lib=Join-Path $runtime 'lib'
if(-not(Test-Path -LiteralPath $jar)){throw 'Build the app with mvnw package first'}
if(Test-Path -LiteralPath $ReadyFile){throw 'Use a fresh readiness marker'}
New-Item -ItemType Directory -Force $classes,$lib | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive=[IO.Compression.ZipFile]::OpenRead($jar)
try {
 foreach($entry in $archive.Entries) {
  if($entry.FullName.StartsWith('BOOT-INF/lib/') -and $entry.FullName.EndsWith('.jar')) {
   $destination=Join-Path $lib $entry.Name
   [IO.Compression.ZipFileExtensions]::ExtractToFile($entry,$destination,$true)
  }
 }
} finally {$archive.Dispose()}
$classpath=(Join-Path $root 'target/classes')+';'+$lib+'/*'
& javac -encoding UTF-8 -classpath $classpath -d $classes (Join-Path $PSScriptRoot 'VoiceAcceptance.java')
if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
& java --enable-native-access=ALL-UNNAMED '-Dstdout.encoding=UTF-8' -classpath ($classes+';'+$classpath) dev.mikoto2000.rei.VoiceAcceptance $Bundle $DataDirectory $ReadyFile $Microphone $ExternalConfig
exit $LASTEXITCODE