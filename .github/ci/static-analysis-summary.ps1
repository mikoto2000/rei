$ErrorActionPreference = 'Stop'
$repository = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$target = Join-Path $repository 'target'
New-Item -ItemType Directory -Force -Path $target | Out-Null
$lines = [Collections.Generic.List[string]]::new()
$lines.Add('# Java static analysis')
$lines.Add('')
$lines.Add('Full XML/HTML reports and Maven diagnostic logs are in the static-analysis artifact.')
$lines.Add('')
$lines.Add('| Tool | Step outcome | Findings |')
$lines.Add('| --- | --- | ---: |')
$invalidReport = $false
$details = [Collections.Generic.List[string]]::new()
foreach ($tool in @('checkstyle', 'spotbugs')) {
    $outcome = if ($tool -eq 'checkstyle') { $env:CHECKSTYLE_OUTCOME } else { $env:SPOTBUGS_OUTCOME }
    if (-not $outcome) { $outcome = 'local inventory' }
    $filename = if ($tool -eq 'checkstyle') { 'checkstyle-result.xml' } else { 'spotbugsXml.xml' }
    $path = Join-Path $target $filename
    try {
        # Disable DTD/entity resolution even when parsing a PR-produced report.
        $settings = [Xml.XmlReaderSettings]::new()
        $settings.DtdProcessing = [Xml.DtdProcessing]::Prohibit
        $settings.XmlResolver = $null
        $reader = [Xml.XmlReader]::Create($path, $settings)
        try {
            $report = [Xml.XmlDocument]::new()
            $report.XmlResolver = $null
            $report.Load($reader)
        } finally { $reader.Dispose() }
        $xpath = if ($tool -eq 'checkstyle') { '/checkstyle/file/error' } else { '/BugCollection/BugInstance' }
        $root = if ($tool -eq 'checkstyle') { 'checkstyle' } else { 'BugCollection' }
        if ($report.DocumentElement.Name -ne $root) { throw 'Unexpected report root' }
        $findings = $report.SelectNodes($xpath)
        $lines.Add("| $tool | $outcome | $($findings.Count) |")
        $attribute = if ($tool -eq 'checkstyle') { 'source' } else { 'type' }
        $groups = $findings | Group-Object { $_.GetAttribute($attribute) } | Sort-Object Count -Descending
        $details.Add('')
        $details.Add("## $tool finding types (top 10)")
        $details.Add('')
        foreach ($group in @($groups | Select-Object -First 10)) {
            $safeName = $group.Name -replace '[^A-Za-z0-9_.$]', ''
            $details.Add("- ${safeName}: $($group.Count)")
        }
    } catch {
        $invalidReport = $true
        $lines.Add("| $tool | $outcome | report missing or unreadable; inspect Maven log |")
    }
}
$lines.Add('')
$lines.Add('Checkstyle scans main and test Java sources; SpotBugs scans main bytecode at Max effort / Low threshold.')
$lines.AddRange($details)
$summary = $lines -join "`n"
$summaryPath = Join-Path $target 'static-analysis-summary.md'
[IO.File]::WriteAllText($summaryPath, $summary + "`n", [Text.UTF8Encoding]::new($false))
Write-Output $summary
if ($env:GITHUB_STEP_SUMMARY) { Add-Content -LiteralPath $env:GITHUB_STEP_SUMMARY -Value $summary -Encoding utf8 }
if ($invalidReport) { throw 'An analysis report is missing or unreadable. Summary and available logs were retained.' }
