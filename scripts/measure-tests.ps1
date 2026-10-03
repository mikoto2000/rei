param(
    [Parameter(Mandatory)][ValidatePattern('^[a-zA-Z0-9-]+$')][string]$Label,
    [string[]]$MavenArguments = @('test'),
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$Repository = '',
    [switch]$Offline
)
$ErrorActionPreference = 'Stop'
if ($JavaHome) {
    $env:JAVA_HOME = $JavaHome
    $env:PATH = "$JavaHome\bin;$env:PATH"
}
$root = (Resolve-Path "$PSScriptRoot/..").Path
Push-Location $root
try {
    $output = Join-Path $root "target/test-performance/$Label"
    if (Test-Path $output) { throw "Label already exists: $Label; use a fresh label" }
    New-Item -ItemType Directory -Force $output | Out-Null
    $env:REI_DATA_DIR = Join-Path $root 'target/test-performance/data'
    $reports = Join-Path $root 'target/surefire-reports'
    # Remove generated XML only, with the absolute location constrained to this checkout.
    if (Test-Path $reports) {
        $resolved = (Resolve-Path $reports).Path
        if (!$resolved.StartsWith($root + [IO.Path]::DirectorySeparatorChar)) { throw 'Reports outside workspace' }
        Get-ChildItem -LiteralPath $resolved -Filter 'TEST-*.xml' |
            ForEach-Object { Remove-Item -LiteralPath $_.FullName }
    }
    $arguments = @()
    if ($Offline) { $arguments += '-o' }
    if ($Repository) { $arguments += "-Dmaven.repo.local=$Repository" }
    $arguments += $MavenArguments
    $arguments | ConvertTo-Json | Set-Content "$output/arguments.json" -Encoding utf8
    $watch = [Diagnostics.Stopwatch]::StartNew()
    & "$root/mvnw.cmd" @arguments *> "$output/maven.log"
    $result = $LASTEXITCODE
    $watch.Stop()
    [ordered]@{Seconds=$watch.Elapsed.TotalSeconds; ExitCode=$result; JavaHome=$env:JAVA_HOME} |
        ConvertTo-Json | Set-Content "$output/timing.json" -Encoding utf8
    if (Test-Path $reports) {
        Copy-Item -LiteralPath $reports -Destination "$output/xml" -Recurse
        & "$PSScriptRoot/test-performance-report.ps1" -Reports "$output/xml" -Output "$output/report" -WallSeconds $watch.Elapsed.TotalSeconds
    }
    if ($result -ne 0) { throw "Maven failed ($result): $output/maven.log" }
} finally { Pop-Location }
