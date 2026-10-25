param([string]$DiskInstance = '_Total', [int]$EventsPerSecond = 50, [switch]$StreamingCommits)

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
    $taskIoModes = if ($StreamingCommits) { @('optimized', 'coordinated') } else { @('baseline', 'optimized') }
    foreach ($mode in $taskIoModes) {
        $arguments = @('-o', '-q', '-Pfull', '-Dtest=AnswerGenerationIoBenchmarkTest', '-Drei.io.benchmark=true', "-Drei.io.events-per-second=$EventsPerSecond", 'test')
        if ($mode -ne 'baseline') { $arguments += '-Drei.io.optimized=true' }
        if ($mode -eq 'coordinated') { $arguments += '-Drei.io.coordinated=true' }
        $taskIoPageSuffix = ''
        if ($StreamingCommits) {
            $taskIoPageSize = if ($mode -eq 'coordinated') { 1024 } else { 4096 }
            $arguments += "-Drei.io.page-size=$taskIoPageSize"
            $taskIoPageSuffix = "-page$taskIoPageSize"
        }
        $log = Join-Path $repository "target\answer-io-$mode.log"
        & (Join-Path $repository 'mvnw.cmd') @arguments *> $log
        if ($LASTEXITCODE -ne 0) { throw "Benchmark failed; inspect $log" }
        Write-Output "Saved target/answer-generation-io-$mode$taskIoPageSuffix.json"
    }
} finally {
    Pop-Location
    $env:TEMP = $taskPreviousTemp
    $env:TMP = $taskPreviousTmp
    $env:REI_IO_DISK = $taskPreviousDisk
}
