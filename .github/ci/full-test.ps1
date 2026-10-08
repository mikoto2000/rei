param([ValidateRange(1, 1800)][int]$TimeoutSeconds = 1200)

$ErrorActionPreference = 'Stop'
$repository = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$artifacts = Join-Path $repository 'target'
New-Item -ItemType Directory -Force -Path $artifacts | Out-Null
$stdout = Join-Path $artifacts 'ci-full.stdout.log'
$stderr = Join-Path $artifacts 'ci-full.stderr.log'
$wrapper = Join-Path $repository 'mvnw.cmd'
$commandText = '"' + $wrapper + '" -B -Pfull "-Djava.io.tmpdir=' + $env:TEMP + '" test'
$process = Start-Process -FilePath $env:ComSpec -ArgumentList ('/d /s /c "' + $commandText + '"') `
    -WorkingDirectory $repository -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput $stdout -RedirectStandardError $stderr
$elapsed = [Diagnostics.Stopwatch]::StartNew()
$nextProgress = 15
$timedOut = $false
try {
    while (-not $process.WaitForExit(1000)) {
        if ($elapsed.Elapsed.TotalSeconds -ge $TimeoutSeconds) {
            $timedOut = $true
            # This Process object owns the Maven wrapper started above, including its descendants.
            $process.Kill($true)
            if (-not $process.WaitForExit(10000)) { throw 'Owned Maven process did not terminate' }
            break
        }
        if ($elapsed.Elapsed.TotalSeconds -ge $nextProgress) {
            Write-Output ('Full regression elapsed: {0:N0}s' -f $elapsed.Elapsed.TotalSeconds)
            Get-Content -LiteralPath $stdout -Tail 5 -ErrorAction SilentlyContinue
            $nextProgress += 15
        }
    }
} finally {
    if (-not $process.HasExited) { $process.Kill($true) }
    Get-Content -LiteralPath $stdout -ErrorAction SilentlyContinue
    Get-Content -LiteralPath $stderr -ErrorAction SilentlyContinue
}
if ($timedOut) {
    Write-Output "Full regression exceeded ${TimeoutSeconds}s; partial logs and reports are retained."
    exit 124
}
$process.Refresh()
$exitCode = $process.ExitCode
if ($null -eq $exitCode) { throw 'Maven exit code is unavailable' }
exit $exitCode
