$ErrorActionPreference = 'Stop'
$root = Join-Path ([IO.Path]::GetTempPath()) ('rei-performance-' + [guid]::NewGuid())
New-Item -ItemType Directory "$root/surefire-reports","$root/failsafe-reports","$root/empty" | Out-Null
function Assert($condition, $message) { if (!$condition) { throw $message } }
function Reject($paths) {
    $rejected = $false
    try { & "$PSScriptRoot/test-performance-report.ps1" -Reports $paths -Output "$root/rejected" | Out-Null } catch { $rejected = $true }
    Assert $rejected 'Invalid/duplicate/empty report input must fail'
}
try {
    @'
<testsuite name="Example" tests="4" failures="1" errors="1" skipped="1" time="1.25">
<testcase classname="Example" name="parameter[1]: 日本語" time="0.125"/>
<testcase classname="Example" name="failure" time="0.25"><failure/></testcase>
<testcase classname="Example" name="error" time="0.5"><error/></testcase>
<testcase classname="Example" name="skip" time="0"><skipped/></testcase>
</testsuite>
'@ | Set-Content "$root/surefire-reports/TEST-Example.xml" -Encoding utf8
    '<testsuite name="Integration" tests="1" failures="0" errors="0" skipped="0" time="2.5"><testcase classname="Integration" name="dynamic[1]" time="2.25"/></testsuite>' |
        Set-Content "$root/failsafe-reports/TEST-Integration.xml" -Encoding utf8
    & "$PSScriptRoot/test-performance-report.ps1" -Reports @("$root/surefire-reports","$root/failsafe-reports") -Output "$root/result" -WallSeconds 2 | Out-Null
    $summary = Get-Content "$root/result/summary.json" -Raw | ConvertFrom-Json
    Assert ($summary.Tests -eq 5 -and $summary.Classes -eq 2) 'Test/class totals'
    Assert ($summary.ExecutedClasses -eq 2) 'Classes with executed cases'
    Assert ($summary.Failures -eq 1 -and $summary.Errors -eq 1 -and $summary.Skipped -eq 1 -and $summary.Passed -eq 2) 'Result totals'
    Assert ($summary.SuiteSeconds -eq 3.75 -and $summary.WallSeconds -eq 2) 'Suite sum differs from parallel wall time'
    Assert ($summary.Providers.Surefire.Tests -eq 4 -and $summary.Providers.Failsafe.SuiteSeconds -eq 2.5) 'Provider totals'
    Assert ($summary.Top20SuitePercent -eq 100 -and $summary.Buckets.'1-5s' -eq 2) 'Distribution'
    Assert ([Math]::Abs($summary.TestCountTimePearson + 1) -lt 0.000001) 'Pearson correlation'
    $classRows = @(Import-Csv "$root/result/classes.csv")
    Assert ([Math]::Abs([double]$classRows[0].SuitePercent - 200/3) -lt 0.000001) 'Suite percentage denominator'
    $methods = @(Import-Csv "$root/result/methods.csv")
    Assert (($methods | Where-Object Method -eq 'parameter[1]: 日本語').Seconds -eq '0.125') 'Decimal and parameter identity'
    Assert (($methods | Where-Object Method -eq 'failure').Result -eq 'Failed') 'Failure result'
    Assert (($methods | Where-Object Method -eq 'error').Result -eq 'Error') 'Error result'
    Assert (($methods | Where-Object Method -eq 'skip').Result -eq 'Skipped') 'Skipped result'
    Reject @("$root/empty")
    Copy-Item "$root/surefire-reports/TEST-Example.xml" "$root/surefire-reports/TEST-duplicate.xml"
    Reject @("$root/surefire-reports")
    Remove-Item -LiteralPath "$root/surefire-reports/TEST-duplicate.xml"
    '<testsuite' | Set-Content "$root/surefire-reports/TEST-invalid.xml"
    Reject @("$root/surefire-reports")
    '' | Set-Content "$root/surefire-reports/TEST-invalid.xml"
    Reject @("$root/surefire-reports")
    '<!DOCTYPE testsuite [<!ENTITY secret SYSTEM "file:///missing">]><testsuite name="unsafe" tests="0" failures="0" errors="0" skipped="0" time="0"/>' |
        Set-Content "$root/surefire-reports/TEST-invalid.xml"
    Reject @("$root/surefire-reports")
    Remove-Item -LiteralPath "$root/surefire-reports/TEST-invalid.xml"
    '<testsuite name="Zero" tests="0" failures="0" errors="0" skipped="0" time="0"/>' |
        Set-Content "$root/empty/TEST-zero.xml"
    & "$PSScriptRoot/test-performance-report.ps1" -Reports "$root/empty" -Output "$root/zero" | Out-Null
    $zero = Get-Content "$root/zero/summary.json" -Raw | ConvertFrom-Json
    Assert ($zero.Tests -eq 0 -and $zero.SuiteSeconds -eq 0 -and $null -eq $zero.TestCountTimePearson) 'Zero duration and undefined correlation'
    Assert ($zero.Providers.Failsafe.Tests -eq 0 -and $zero.Providers.Failsafe.SuiteSeconds -eq 0) 'Absent provider has zero totals'
    Assert ($null -eq $zero.WallSeconds) 'Unmeasured wall time must not be reported as zero'
    Assert ($zero.Classes -eq 1 -and $zero.ExecutedClasses -eq 0) 'Reported suite differs from executed class'
    New-Item -ItemType Directory "$root/skiponly" | Out-Null
    '<testsuite name="Skipped" tests="1" failures="0" errors="0" skipped="1" time="0"><testcase name="disabled" time="0"><skipped/></testcase></testsuite>' |
        Set-Content "$root/skiponly/TEST-skipped.xml"
    & "$PSScriptRoot/test-performance-report.ps1" -Reports "$root/skiponly" -Output "$root/skip-result" | Out-Null
    $skipOnly = Get-Content "$root/skip-result/summary.json" -Raw | ConvertFrom-Json
    Assert ($skipOnly.Tests -eq 1 -and $skipOnly.Classes -eq 1 -and $skipOnly.ExecutedClasses -eq 0) 'All-skipped class was not executed'
    foreach ($duration in @('0.999','1','5','10','30')) {
        "<testsuite name='Boundary$duration' tests='0' failures='0' errors='0' skipped='0' time='$duration'/>" |
            Set-Content "$root/empty/TEST-$duration.xml"
    }
    & "$PSScriptRoot/test-performance-report.ps1" -Reports "$root/empty" -Output "$root/bounds" | Out-Null
    $bounds = Get-Content "$root/bounds/summary.json" -Raw | ConvertFrom-Json
    Assert ($bounds.Buckets.'<1s' -eq 2 -and $bounds.Buckets.'1-5s' -eq 1 -and $bounds.Buckets.'5-10s' -eq 1 -and $bounds.Buckets.'10-30s' -eq 1 -and $bounds.Buckets.'>=30s' -eq 1) 'Bucket boundaries'
    Write-Output 'PASS: Surefire/Failsafe, outcomes, decimals, identity, distribution, parallel wall, empty, malformed, duplicates'
} finally {
    $resolved = [IO.Path]::GetFullPath($root)
    if (!$resolved.StartsWith([IO.Path]::GetFullPath([IO.Path]::GetTempPath())) -or (Split-Path $resolved -Leaf) -notlike 'rei-performance-*') { throw 'Unsafe fixture cleanup path' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
