$ErrorActionPreference = 'Stop'
$probe = Join-Path ([IO.Path]::GetTempPath()) ('rei-analysis-gate-' + [Guid]::NewGuid())
New-Item -ItemType Directory -Force -Path "$probe/.github/ci", "$probe/target" | Out-Null
Copy-Item -LiteralPath "$PSScriptRoot/static-analysis-gate.ps1" -Destination "$probe/.github/ci"
$gate = "$probe/.github/ci/static-analysis-gate.ps1"
function Write-Reports([string]$checkstyle = '', [string]$spotbugs = '', [string]$errors = "<Errors errors='0'/>") {
    Set-Content "$probe/target/checkstyle-result.xml" "<checkstyle version='14.3.0'><file name='Example.java'>$checkstyle</file></checkstyle>"
    Set-Content "$probe/target/spotbugsXml.xml" "<BugCollection version='4.10.5'>$spotbugs$errors</BugCollection>"
}
function Expect-Failure([string]$message, [string]$reason = '') {
    try { & $gate | Out-Null } catch {
        if ($reason -and -not $_.Exception.Message.Contains($reason)) { throw }
        Write-Output "PASS: $message"
        return
    }
    throw "Gate unexpectedly passed: $message"
}
try {
    Write-Reports
    & $gate | Out-Null
    Write-Output 'PASS: zero findings accepted'
    Write-Reports -checkstyle "<error line='1' source='ExampleCheck'/>"
    Expect-Failure 'one Checkstyle finding rejected' 'found 1 findings'
    Write-Reports -spotbugs "<BugInstance type='EXAMPLE' instanceHash='old'/>"
    Expect-Failure 'one SpotBugs finding rejected' 'found 1 findings'
    Write-Reports -errors "<Errors errors='1'/>"
    Expect-Failure 'incomplete SpotBugs analysis rejected' 'analysis is incomplete'
    Write-Reports -errors "<Errors errors='0'><MissingClass>Example</MissingClass></Errors>"
    Expect-Failure 'missing SpotBugs class rejected' 'analysis is incomplete'
    Write-Reports -errors ''
    Expect-Failure 'missing SpotBugs error section rejected' 'analysis is incomplete'
    Write-Reports
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
