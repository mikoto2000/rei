param(
    [string[]]$Reports = @('target/surefire-reports'),
    [string]$Output = 'target/test-performance/report',
    [ValidateRange(0, [double]::MaxValue)][double]$WallSeconds = 0
)
$ErrorActionPreference = 'Stop'
$culture = [Globalization.CultureInfo]::InvariantCulture
# Each directory must describe one run. Never silently add copies or reruns together.
$files = @($Reports | ForEach-Object { Get-ChildItem -LiteralPath $_ -Filter 'TEST-*.xml' })
if (!$files.Count) { throw 'No test reports' }
$seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
$methods = [Collections.Generic.List[object]]::new()
$classes = @(foreach ($file in $files) {
    $provider = if ($file.Directory.Name -eq 'failsafe-reports') { 'Failsafe' } else { 'Surefire' }
    $settings = [Xml.XmlReaderSettings]::new()
    $settings.DtdProcessing = [Xml.DtdProcessing]::Prohibit
    $settings.XmlResolver = $null
    $reader = [Xml.XmlReader]::Create($file.FullName, $settings)
    try { $xml = [Xml.XmlDocument]::new(); $xml.Load($reader) } finally { $reader.Dispose() }
    $suite = $xml.DocumentElement
    if ($suite.LocalName -ne 'testsuite' -or !$suite.GetAttribute('name')) { throw "Expected named testsuite: $($file.Name)" }
    $name = $suite.GetAttribute('name')
    if (!$seen.Add("${provider}:$name")) { throw "Duplicate suite (use separate output per run): ${provider}:$name" }
    $seconds = [double]::Parse($suite.GetAttribute('time'), $culture)
    if (![double]::IsFinite($seconds) -or $seconds -lt 0) { throw 'Invalid suite duration' }
    $times = [Collections.Generic.List[double]]::new()
    foreach ($case in $suite.SelectNodes('testcase')) {
        $time = [double]::Parse($case.GetAttribute('time'), $culture)
        if (![double]::IsFinite($time) -or $time -lt 0) { throw 'Invalid testcase duration' }
        $times.Add($time)
        $result = if ($case.SelectSingleNode('error')) { 'Error' } elseif ($case.SelectSingleNode('failure')) { 'Failed' } elseif ($case.SelectSingleNode('skipped')) { 'Skipped' } else { 'Passed' }
        $className = $case.GetAttribute('classname')
        if (!$className) { $className = $name }
        $methods.Add([pscustomobject]@{Provider=$provider; Class=$className; Suite=$name; Method=$case.GetAttribute('name'); Seconds=$time; Result=$result})
    }
    $counts = @{}
    foreach ($key in @('tests','failures','errors','skipped')) {
        $counts[$key] = [int]::Parse($suite.GetAttribute($key), $culture)
        if ($counts[$key] -lt 0) { throw 'Negative suite count' }
    }
    $passed = $counts.tests - $counts.failures - $counts.errors - $counts.skipped
    if ($passed -lt 0) { throw 'Inconsistent suite counts' }
    [pscustomobject]@{
        Provider=$provider; Class=$name; Tests=$counts.tests; Passed=$passed
        Failures=$counts.failures; Errors=$counts.errors; Skipped=$counts.skipped
        TotalSeconds=$seconds; SuitePercent=0.0
        AverageSeconds=if ($times.Count) { ($times | Measure-Object -Sum).Sum / $times.Count } else { 0 }
        MaxSeconds=if ($times.Count) { ($times | Measure-Object -Maximum).Maximum } else { 0 }
    }
})
$classes = @($classes | Sort-Object TotalSeconds -Descending)
$total = ($classes | Measure-Object TotalSeconds -Sum).Sum
foreach ($row in $classes) { if ($total) { $row.SuitePercent = 100 * $row.TotalSeconds / $total } }
function Totals($rows) {
    $result = [ordered]@{Classes=@($rows).Count}
    foreach ($key in @('Tests','Passed','Failures','Errors','Skipped')) { $result[$key] = [long](($rows | Measure-Object $key -Sum).Sum) }
    $result.SuiteSeconds = [double](($rows | Measure-Object TotalSeconds -Sum).Sum)
    return $result
}
$summary = Totals $classes
$summary.WallSeconds = $WallSeconds
$summary.Providers = [ordered]@{}
foreach ($provider in @('Surefire','Failsafe')) { $summary.Providers[$provider] = Totals @($classes | Where-Object Provider -eq $provider) }
foreach ($n in @(10,20,50,100)) {
    $seconds = ($classes | Select-Object -First $n | Measure-Object TotalSeconds -Sum).Sum
    $summary["Top${n}SuitePercent"] = if ($total) { 100 * $seconds / $total } else { 0 }
}
$summary.Buckets = [ordered]@{'<1s'=0; '1-5s'=0; '5-10s'=0; '10-30s'=0; '>=30s'=0}
foreach ($row in $classes) {
    $key = if ($row.TotalSeconds -lt 1) { '<1s' } elseif ($row.TotalSeconds -lt 5) { '1-5s' } elseif ($row.TotalSeconds -lt 10) { '5-10s' } elseif ($row.TotalSeconds -lt 30) { '10-30s' } else { '>=30s' }
    $summary.Buckets[$key]++
}
$meanX = ($classes | Measure-Object Tests -Average).Average
$meanY = ($classes | Measure-Object TotalSeconds -Average).Average
$xx = 0.0; $yy = 0.0; $xy = 0.0
foreach ($row in $classes) {
    $x = $row.Tests - $meanX; $y = $row.TotalSeconds - $meanY
    $xx += $x*$x; $yy += $y*$y; $xy += $x*$y
}
$summary.TestCountTimePearson = if ($xx -gt 0 -and $yy -gt 0) { $xy / [Math]::Sqrt($xx*$yy) } else { $null }
$summary.TimingNote = 'SuiteSeconds sums XML suite durations; it is not wall-clock or Maven test-phase time, including when tests run in parallel. Inputs must belong to a single run. Duplicate provider/suite identities are rejected; testcase names and repeated invocations are preserved. Directory name failsafe-reports identifies Failsafe; other directories are Surefire.'
New-Item -ItemType Directory -Force -Path $Output | Out-Null
$classes | Export-Csv "$Output/classes.csv" -NoTypeInformation -Encoding utf8
$methods | Sort-Object Seconds -Descending | Export-Csv "$Output/methods.csv" -NoTypeInformation -Encoding utf8
$summary | ConvertTo-Json -Depth 6 | Set-Content "$Output/summary.json" -Encoding utf8
$lines = @('| rank | test class | test count | total s | average method s | max method s |', '| ---: | --- | ---: | ---: | ---: | ---: |')
$rank = 0
foreach ($row in ($classes | Select-Object -First 50)) {
    $rank++
    $lines += '| {0} | {1} | {2} | {3:F3} | {4:F3} | {5:F3} |' -f $rank,$row.Class,$row.Tests,$row.TotalSeconds,$row.AverageSeconds,$row.MaxSeconds
}
$lines | Set-Content "$Output/top50.md" -Encoding utf8
$summary

