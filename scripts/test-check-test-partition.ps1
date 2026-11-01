$ErrorActionPreference = 'Stop'
$probe = Join-Path ([IO.Path]::GetTempPath()) ('rei-partition-' + [guid]::NewGuid())
New-Item -ItemType Directory "$probe/fast","$probe/integration","$probe/full","$probe/before" | Out-Null
function Assert($condition, $message) { if (!$condition) { throw $message } }
function Suite($directory, $cases, [int]$failures=0) {
    $skips = @($cases | Where-Object { $_ -match '<skipped' }).Count
    "<testsuite name='Fixture' tests='$($cases.Count)' failures='$failures' errors='0' skipped='$skips'>$($cases -join '')</testsuite>" |
        Set-Content "$probe/$directory/TEST-Fixture.xml" -Encoding utf8
}
$a = '<testcase name="parameter[1]"/>'
$b = '<testcase name="dynamic[2]"/>'
$skip = '<testcase name="optional"><skipped message="Fixture unavailable"/></testcase>'
function Check {
    & "$PSScriptRoot/check-test-partition.ps1" -Fast "$probe/fast" -Integration "$probe/integration" -Full "$probe/full" -Before "$probe/before" -ExpectedSkips "$probe/skips.json" -Output "$probe/result.json"
}
function Reject($description) {
    $failed=$false
    try { Check | Out-Null } catch { $failed=$true }
    Assert $failed "Must reject: $description"
}
function Reset {
    Suite fast @($a,$skip); Suite integration @($b); Suite full @($a,$b,$skip); Suite before @($a,$b,$skip)
    @([ordered]@{Suite='Fixture'; Method='optional'; Reason='Fixture unavailable'}) | ConvertTo-Json -AsArray |
        Set-Content "$probe/skips.json" -Encoding utf8
}
try {
    Reset
    Check | Out-Null
    $result = Get-Content "$probe/result.json" -Raw | ConvertFrom-Json
    Assert ($result.Fast -eq 2 -and $result.Integration -eq 1 -and $result.Full -eq 3) 'Counts include skipped identities'
    Assert ($result.Skips.Full[0].Reason -eq 'Fixture unavailable') 'Skip reason is recorded'
    Suite fast @($a); Reject 'skipped identity missing from partition'
    Reset; Suite integration @($a,$b); Reject 'overlap'
    Reset; Suite full @($a,$skip); Reject 'partition has extra test'
    Reset; Suite fast @($a); Suite full @($a,$b); Reject 'existing skipped identity disappeared from full'
    Reset; '[]' | Set-Content "$probe/skips.json"; Reject 'unlisted skip'
    Reset; Suite fast @($a,$skip.Replace('unavailable','changed')); Reject 'skip reason changed'
    Reset; Suite integration @('<testcase name="dynamic[2]"><failure/></testcase>') 1; Reject 'failure'
    Reset; '<testsuite name="Fixture" tests="1" failures="0" errors="1" skipped="0"><testcase name="dynamic[2]"><error/></testcase></testsuite>' |
        Set-Content "$probe/integration/TEST-Fixture.xml"; Reject 'error'
    Reset; Suite fast @($a,$a,$skip); Reject 'duplicate invocation identity'
    Reset
    $newSkip='<testcase name="parameter[1]"><skipped message="Newly unavailable"/></testcase>'
    @(@{Suite='Fixture';Method='optional';Reason='Fixture unavailable'},@{Suite='Fixture';Method='parameter[1]';Reason='Newly unavailable'}) |
        ConvertTo-Json | Set-Content "$probe/skips.json"
    Suite fast @($newSkip,$skip); Suite full @($newSkip,$b,$skip)
    Reject 'previously passing test became skipped even if allowlisted'
    Reset
    '<testsuite name="Fixture" tests="2" failures="0" errors="0" skipped="0"><testcase name="dynamic[2]"/></testsuite>' |
        Set-Content "$probe/integration/TEST-Fixture.xml"
    Reject 'incomplete testcase inventory'
    Reset; Suite fast @($a,$skip,'<testcase name="newSecurity[1]"/>'); Suite full @($a,$b,$skip,'<testcase name="newSecurity[1]"/>')
    Check | Out-Null
    $result = Get-Content "$probe/result.json" -Raw | ConvertFrom-Json
    Assert ($result.Added.Count -eq 1) 'New security tests may extend full without losing existing tests'
    Write-Output 'PASS: partition union/disjointness, parameters/dynamic IDs, baseline retention, expected/unexpected skips, failures/errors, duplicates'
} finally {
    $resolved = [IO.Path]::GetFullPath($probe)
    if (!$resolved.StartsWith([IO.Path]::GetFullPath([IO.Path]::GetTempPath())) -or (Split-Path $resolved -Leaf) -notlike 'rei-partition-*') { throw 'Unsafe cleanup path' }
    Remove-Item -LiteralPath $resolved -Force -Recurse
}
