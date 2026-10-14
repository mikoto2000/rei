$ErrorActionPreference = 'Stop'
$probe = Join-Path ([IO.Path]::GetTempPath()) ('rei-analysis-gate-' + [Guid]::NewGuid())
New-Item -ItemType Directory -Force -Path "$probe/.github/ci", "$probe/config", "$probe/target", "$probe/src/main/java" | Out-Null
Copy-Item -LiteralPath "$PSScriptRoot/static-analysis-gate.ps1" -Destination "$probe/.github/ci"
$gate = "$probe/.github/ci/static-analysis-gate.ps1"
$source = "$probe/src/main/java/Example.java"
function Write-Checkstyle([int]$line = 1, [int]$count = 1) {
    $escaped = [Security.SecurityElement]::Escape($source)
    $errorXml = "<error line='$line' source='ExampleCheck'/>" * $count
    Set-Content "$probe/target/checkstyle-result.xml" "<checkstyle version='14.3.0'><file name='$escaped'>$errorXml</file></checkstyle>"
}
function Write-Spotbugs([string]$hash = 'old', [int]$errors = 0) {
    Set-Content "$probe/target/spotbugsXml.xml" "<BugCollection version='4.10.5'><BugInstance type='EXAMPLE' instanceHash='$hash'/><Errors errors='$errors'/></BugCollection>"
}
function Expect-Failure([string]$message) {
    $failed = $false
    try { & $gate | Out-Null } catch { $failed = $true }
    if (-not $failed) { throw "Gate unexpectedly passed: $message" }
    Write-Output "PASS: $message"
}
try {
    Set-Content $source 'class Example {}'
    Write-Checkstyle
    Write-Spotbugs
    & $gate -WriteBaseline | Out-Null
    & $gate | Out-Null
    Write-Output 'PASS: unchanged existing findings accepted'
    Set-Content $source "`nclass Example {}"
    Write-Checkstyle -line 2
    & $gate | Out-Null
    Write-Output 'PASS: moved unchanged source line accepted'
    Write-Checkstyle -line 2 -count 2
    Expect-Failure 'increased identical Checkstyle finding count rejected'
    Write-Checkstyle -line 2
    Set-Content $source "`nclass Different {}"
    Expect-Failure 'changed offending source line rejected'
    Set-Content $source "`nclass Example {}"
    Write-Spotbugs -hash 'new'
    Expect-Failure 'new SpotBugs instance rejected even at unchanged total count'
    Write-Spotbugs -errors 1
    Expect-Failure 'incomplete SpotBugs analysis rejected'
    Set-Content "$probe/target/checkstyle-result.xml" 'broken'
    Expect-Failure 'malformed report rejected'
    Remove-Item -LiteralPath "$probe/target/checkstyle-result.xml"
    Expect-Failure 'missing report rejected'
} finally {
    $resolved = [IO.Path]::GetFullPath($probe)
    $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
    if (-not $resolved.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe fixture cleanup path' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
