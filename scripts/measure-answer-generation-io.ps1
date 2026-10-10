param([string]$DiskInstance = '_Total', [int]$EventsPerSecond = 50)

$ErrorActionPreference = 'Stop'
$repository = Split-Path $PSScriptRoot -Parent
$temporary = Join-Path $repository 'target\answer-io-temp'
New-Item -ItemType Directory -Force -Path $temporary | Out-Null
$taskPreviousTemp = $env:TEMP
$taskPreviousTmp = $env:TMP
$taskPreviousDisk = $env:REI_IO_DISK
Push-Location $repository
try {
    $env:TEMP = $temporary
    $env:TMP = $temporary
    $env:REI_IO_DISK = $DiskInstance
    foreach ($mode in @('baseline', 'optimized')) {
        $arguments = @('-o', '-q', '-Pfull', '-Dtest=AnswerGenerationIoBenchmarkTest', '-Drei.io.benchmark=true', "-Drei.io.events-per-second=$EventsPerSecond", 'test')
        if ($mode -eq 'optimized') { $arguments += '-Drei.io.optimized=true' }
        $log = Join-Path $repository "target\answer-io-$mode.log"
        & (Join-Path $repository 'mvnw.cmd') @arguments *> $log
        if ($LASTEXITCODE -ne 0) { throw "Benchmark failed; inspect $log" }
        Write-Output "Saved target/answer-generation-io-$mode.json"
    }
} finally {
    Pop-Location
    $env:TEMP = $taskPreviousTemp
    $env:TMP = $taskPreviousTmp
    $env:REI_IO_DISK = $taskPreviousDisk
}
