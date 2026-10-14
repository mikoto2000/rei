param([switch]$WriteBaseline)
$ErrorActionPreference = 'Stop'
$repository = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$target = Join-Path $repository 'target'
$baselinePath = Join-Path $repository 'config/static-analysis-baseline.json'
$current = [ordered]@{ checkstyle = @{}; spotbugs = @{} }
$sourceLines = @{}
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
function Add-Finding([string]$tool, [string]$signature) {
    $bytes = [Text.Encoding]::UTF8.GetBytes($signature)
    $key = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant()
    if (-not $current[$tool].ContainsKey($key)) { $current[$tool][$key] = 0 }
    $current[$tool][$key]++
}
$checkstyle = Read-Report 'checkstyle-result.xml' 'checkstyle'
if ($checkstyle.DocumentElement.GetAttribute('version') -ne '14.3.0') { throw 'Checkstyle report version differs from baseline policy' }
foreach ($file in $checkstyle.SelectNodes('/checkstyle/file')) {
    $relative = [IO.Path]::GetRelativePath($repository, $file.GetAttribute('name')).Replace('\', '/')
    if ($relative.StartsWith('../') -or [IO.Path]::IsPathRooted($relative)) { throw 'Source outside repository' }
    foreach ($finding in $file.SelectNodes('error')) {
        if (-not $sourceLines.ContainsKey($relative)) {
            $sourceLines[$relative] = [IO.File]::ReadAllLines((Join-Path $repository $relative), [Text.Encoding]::UTF8)
        }
        $line = [int]$finding.GetAttribute('line')
        if ($line -lt 1 -or $line -gt $sourceLines[$relative].Count) { throw 'Invalid source line' }
        # Exact source text (apart from surrounding whitespace) anchors known debt.
        # Line numbers and localized messages are deliberately not fingerprinted.
        $signature = $relative + "`n" + $finding.GetAttribute('source') + "`n" + $sourceLines[$relative][$line - 1].Trim()
        Add-Finding 'checkstyle' $signature
    }
}
$spotbugs = Read-Report 'spotbugsXml.xml' 'BugCollection'
if ($spotbugs.DocumentElement.GetAttribute('version') -ne '4.10.5') { throw 'SpotBugs report version differs from baseline policy' }
if ($spotbugs.SelectNodes('/BugCollection/Errors/Error | /BugCollection/Errors/MissingClass').Count -gt 0 -or
    [int]$spotbugs.DocumentElement.SelectSingleNode('Errors').GetAttribute('errors') -ne 0) {
    throw 'SpotBugs analysis is incomplete; inspect report errors/missing classes'
}
foreach ($finding in $spotbugs.SelectNodes('/BugCollection/BugInstance')) {
    $hash = $finding.GetAttribute('instanceHash')
    if (-not $hash) { throw 'SpotBugs finding has no stable instance hash' }
    Add-Finding 'spotbugs' ($finding.GetAttribute('type') + ':' + $hash)
}
if ($WriteBaseline) {
    # Explicit maintenance operation, never run by CI. Changes require code review.
    $baseline = [ordered]@{
        schema = 1
        reason = 'Existing debt at main 66bf59be, measured 2026-10-14. Not verified false positives; full findings remain visible. Gate only grandfathered signatures and multiplicities.'
        checkstyleVersion = '14.3.0'
        spotbugsVersion = '4.10.5'
        findings = [ordered]@{}
    }
    foreach ($tool in $current.Keys) {
        $sorted = [ordered]@{}
        foreach ($key in @($current[$tool].Keys | Sort-Object)) { $sorted[$key] = $current[$tool][$key] }
        $baseline.findings[$tool] = $sorted
    }
    [IO.File]::WriteAllText($baselinePath, ($baseline | ConvertTo-Json -Depth 5) + "`n", [Text.UTF8Encoding]::new($false))
    Write-Output "Baseline written: $baselinePath (review before committing)"
    return
}
$baseline = Get-Content -LiteralPath $baselinePath -Raw | ConvertFrom-Json -AsHashtable
if ($baseline.schema -ne 1 -or $baseline.checkstyleVersion -ne '14.3.0' -or $baseline.spotbugsVersion -ne '4.10.5') {
    throw 'Baseline schema/analyzer versions differ; review and refresh explicitly'
}
$lines = [Collections.Generic.List[string]]::new()
$lines.Add('# New static-analysis findings gate')
$lines.Add('')
$lines.Add('| Analyzer | Total findings | Existing debt matched | New findings |')
$lines.Add('| --- | ---: | ---: | ---: |')
$newTotal = 0
foreach ($tool in $current.Keys) {
    $total = 0; $matched = 0; $new = 0
    foreach ($key in $current[$tool].Keys) {
        $count = $current[$tool][$key]
        $allowed = 0
        if ($baseline.findings[$tool].ContainsKey($key)) { $allowed = [int]$baseline.findings[$tool][$key] }
        $total += $count
        $matched += [Math]::Min($count, $allowed)
        $new += [Math]::Max(0, $count - $allowed)
    }
    $newTotal += $new
    $lines.Add("| $tool | $total | $matched | $new |")
}
$lines.Add('')
$lines.Add('Existing debt is still included in the complete reports. Baseline updates require explicit review.')
$summary = $lines -join "`n"
[IO.File]::WriteAllText((Join-Path $target 'static-analysis-gate.md'), $summary + "`n", [Text.UTF8Encoding]::new($false))
Write-Output $summary
if ($env:GITHUB_STEP_SUMMARY) { Add-Content -LiteralPath $env:GITHUB_STEP_SUMMARY -Value $summary -Encoding utf8 }
if ($newTotal -gt 0) { throw "Static analysis found $newTotal new findings; inspect complete reports" }
