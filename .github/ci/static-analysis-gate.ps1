$ErrorActionPreference = 'Stop'
$repository = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$target = Join-Path $repository 'target'
function Read-Report([string]$filename, [string]$root) {
    $settings = [Xml.XmlReaderSettings]::new()
    $settings.DtdProcessing = [Xml.DtdProcessing]::Prohibit
    $settings.XmlResolver = $null
    $reader = [Xml.XmlReader]::Create((Join-Path $target $filename), $settings)
    try {
        $report = [Xml.XmlDocument]::new()
        $report.XmlResolver = $null
        $report.Load($reader)
    } finally { $reader.Dispose() }
    if ($report.DocumentElement.Name -ne $root) { throw 'Unexpected report root' }
    return ,$report
}
$checkstyle = Read-Report 'checkstyle-result.xml' 'checkstyle'
if ($checkstyle.DocumentElement.GetAttribute('version') -ne '14.3.0') { throw 'Unexpected Checkstyle report version' }
$spotbugs = Read-Report 'spotbugsXml.xml' 'BugCollection'
if ($spotbugs.DocumentElement.GetAttribute('version') -ne '4.10.5') { throw 'Unexpected SpotBugs report version' }
$errors = $spotbugs.DocumentElement.SelectSingleNode('Errors')
if ($null -eq $errors -or $errors.GetAttribute('errors') -ne '0' -or
    $spotbugs.SelectNodes('/BugCollection/Errors/Error | /BugCollection/Errors/MissingClass').Count -gt 0) {
    throw 'SpotBugs analysis is incomplete; inspect report errors/missing classes'
}
$checkstyleCount = $checkstyle.SelectNodes('/checkstyle/file/error').Count
$spotbugsCount = $spotbugs.SelectNodes('/BugCollection/BugInstance').Count
$summary = @(
    '# Static analysis gate'
    ''
    '| Analyzer | Total findings |'
    '| --- | ---: |'
    "| Checkstyle | $checkstyleCount |"
    "| SpotBugs | $spotbugsCount |"
) -join "`n"
[IO.File]::WriteAllText((Join-Path $target 'static-analysis-gate.md'), $summary + "`n", [Text.UTF8Encoding]::new($false))
Write-Output $summary
if ($env:GITHUB_STEP_SUMMARY) { Add-Content -LiteralPath $env:GITHUB_STEP_SUMMARY -Value $summary -Encoding utf8 }
$total = $checkstyleCount + $spotbugsCount
if ($total -gt 0) { throw "Static analysis found $total findings; inspect complete reports" }
