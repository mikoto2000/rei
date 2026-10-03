param(
    [string]$Reports = 'target/surefire-reports',
    [string]$Output = 'target/test-performance/report',
    [double]$WallSeconds = 0
)
$ErrorActionPreference = 'Stop'
$culture = [Globalization.CultureInfo]::InvariantCulture
$files = @(Get-ChildItem -LiteralPath $Reports -Filter 'TEST-*.xml')
if (!$files.Count) { throw "No test reports in $Reports" }
New-Item -ItemType Directory -Force -Path $Output | Out-Null
$methods = @()
$classes = foreach ($file in $files) {
    [xml]$xml = Get-Content -Raw -LiteralPath $file.FullName
    $suite = $xml.testsuite
    $cases = @($suite.testcase)
    $times = @($cases | ForEach-Object { [double]::Parse($_.time, $culture) })
    foreach ($case in $cases) {
        $methods += [pscustomobject]@{Class=$suite.name; Method=$case.name; Seconds=[double]::Parse($case.time, $culture)}
    }
    [pscustomobject]@{
        Class=$suite.name; Tests=[int]$suite.tests; Failures=[int]$suite.failures
        Errors=[int]$suite.errors; Skipped=[int]$suite.skipped
        TotalSeconds=[double]::Parse($suite.time, $culture)
        AverageSeconds=if ($times.Count) { ($times | Measure-Object -Sum).Sum / $times.Count } else { 0 }
        MaxSeconds=if ($times.Count) { ($times | Measure-Object -Maximum).Maximum } else { 0 }
    }
}
$classes = @($classes | Sort-Object TotalSeconds -Descending)
$classes | Export-Csv "$Output/classes.csv" -NoTypeInformation -Encoding utf8
$methods | Sort-Object Seconds -Descending | Export-Csv "$Output/methods.csv" -NoTypeInformation -Encoding utf8
$total = ($classes | Measure-Object TotalSeconds -Sum).Sum
$summary = [ordered]@{Classes=$classes.Count; Tests=($classes | Measure-Object Tests -Sum).Sum
    Failures=($classes | Measure-Object Failures -Sum).Sum; Errors=($classes | Measure-Object Errors -Sum).Sum
    Skipped=($classes | Measure-Object Skipped -Sum).Sum; SuiteSeconds=$total; WallSeconds=$WallSeconds}
foreach ($n in @(10,50)) {
    $seconds = ($classes | Select-Object -First $n | Measure-Object TotalSeconds -Sum).Sum
    $summary["Top${n}SuitePercent"] = if ($total) { 100 * $seconds / $total } else { 0 }
    $summary["Top${n}WallPercent"] = if ($WallSeconds) { 100 * $seconds / $WallSeconds } else { $null }
}
$summary | ConvertTo-Json | Set-Content "$Output/summary.json" -Encoding utf8
$lines = @('| rank | test class | test count | total s | average method s | max method s |', '| ---: | --- | ---: | ---: | ---: | ---: |')
$rank = 0
foreach ($row in ($classes | Select-Object -First 50)) {
    $rank++
    $lines += '| {0} | {1} | {2} | {3:F3} | {4:F3} | {5:F3} |' -f $rank,$row.Class,$row.Tests,$row.TotalSeconds,$row.AverageSeconds,$row.MaxSeconds
}
$lines | Set-Content "$Output/top50.md" -Encoding utf8
$summary
