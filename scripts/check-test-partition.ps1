[CmdletBinding(DefaultParameterSetName='Profiles')]
param(
    [Parameter(Mandatory, ParameterSetName='Profiles')][string]$Fast,
    [Parameter(Mandatory, ParameterSetName='Profiles')][string]$Integration,
    [Parameter(Mandatory, ParameterSetName='Profiles')][string]$Full,
    [Parameter(ParameterSetName='Profiles')][string]$Before = '',
    [Parameter(Mandatory, ParameterSetName='Shards')][string]$ShardA,
    [Parameter(Mandatory, ParameterSetName='Shards')][string]$ShardB,
    [Parameter(Mandatory, ParameterSetName='Shards')][string]$ShardList,
    [string]$ExpectedSkips = "$PSScriptRoot/../config/test-expected-skips.json",
    [string]$Output = 'target/test-performance/partition.json'
)
$ErrorActionPreference = 'Stop'
$allowed = [Collections.Generic.Dictionary[string,string]]::new([StringComparer]::Ordinal)
if ($ExpectedSkips) {
    foreach ($entry in @(Get-Content -LiteralPath $ExpectedSkips -Raw | ConvertFrom-Json)) {
        if (!$entry.Suite -or !$entry.Method -or !$entry.Reason) { throw 'Expected skip must specify suite, method and reason' }
        $allowed.Add(($entry.Suite+'::'+$entry.Method), $entry.Reason)
    }
}
function Read-Tests([string]$Directory) {
    $tests = [Collections.Generic.Dictionary[string,object]]::new([StringComparer]::Ordinal)
    $files = @(Get-ChildItem -LiteralPath $Directory -Filter 'TEST-*.xml')
    if (!$files.Count) { throw "No XML reports: $Directory" }
    foreach ($file in $files) {
        $settings = [Xml.XmlReaderSettings]::new()
        $settings.DtdProcessing = [Xml.DtdProcessing]::Prohibit
        $settings.XmlResolver = $null
        $reader = [Xml.XmlReader]::Create($file.FullName,$settings)
        try { $xml=[Xml.XmlDocument]::new(); $xml.Load($reader) } finally { $reader.Dispose() }
        $suite=$xml.DocumentElement
        if ($suite.LocalName -ne 'testsuite' -or !$suite.GetAttribute('name')) { throw 'Expected named testsuite' }
        if ([int]::Parse($suite.GetAttribute('failures')) -or [int]::Parse($suite.GetAttribute('errors'))) { throw "Failed tests: $($file.Name)" }
        $cases=$suite.SelectNodes('testcase')
        if ([int]::Parse($suite.GetAttribute('tests')) -ne $cases.Count) { throw "Incomplete testcase inventory: $($file.Name)" }
        $skips=0
        foreach ($case in $cases) {
            if ($case.SelectSingleNode('failure') -or $case.SelectSingleNode('error')) { throw 'Failed testcase' }
            # Qualified suite identity also works when jqwik emits a simple classname.
            $name=$case.GetAttribute('name'); $suiteName=$suite.GetAttribute('name')
            if (!$name) { throw 'Missing testcase name' }
            $key=$suiteName+'::'+$name
            $skip=$case.SelectSingleNode('skipped')
            $reason=''
            if ($skip) {
                $skips++
                $reason=$skip.GetAttribute('message')
                if (!$reason) { $reason=($skip.InnerText.Trim() -split '\r?\n')[0].Trim() -replace '^org\.opentest4j\.TestAbortedException:\s*','' }
                if (!$allowed.ContainsKey($key) -or ![string]::Equals($allowed[$key],$reason,[StringComparison]::Ordinal)) { throw "Unexpected skip: $key; reason=$reason" }
            }
            $tests.Add($key,[pscustomobject]@{Suite=$suiteName; Method=$name; Result=if ($skip) {'Skipped'} else {'Passed'}; Reason=$reason})
        }
        if ([int]::Parse($suite.GetAttribute('skipped')) -ne $skips) { throw 'Inconsistent skipped count' }
    }
    return ,$tests
}

if ($PSCmdlet.ParameterSetName -eq 'Shards') {
    $aTests = Read-Tests $ShardA
    $bTests = Read-Tests $ShardB
    $assigned = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($path in Get-Content -LiteralPath $ShardList) {
        if ($path -cnotmatch '^(?:[\w]+/)*[\w]+\.java$') { throw "Invalid shard A class path: $path" }
        $class = $path.Substring(0, $path.Length - '.java'.Length).Replace('/', '.')
        if (!$assigned.Add($class)) { throw "Duplicate shard A class: $class" }
    }
    $reported = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($test in $aTests.Values) {
        [void]$reported.Add($test.Suite.Split('$')[0])
    }
    if (!$assigned.SetEquals($reported)) {
        $missing = @($assigned | Where-Object { !$reported.Contains($_) })
        $extra = @($reported | Where-Object { !$assigned.Contains($_) })
        throw "Shard A class coverage differs. Missing: $($missing -join ', '); extra: $($extra -join ', ')"
    }
    foreach ($key in $bTests.Keys) {
        if ($aTests.ContainsKey($key)) { throw "Overlapping tests: $key" }
        $class = $bTests[$key].Suite.Split('$')[0]
        if ($assigned.Contains($class)) { throw "Shard B ran a class assigned to shard A: $class" }
    }
    foreach ($key in $allowed.Keys) {
        if ($aTests.ContainsKey($key)) { $test = $aTests[$key] }
        elseif ($bTests.ContainsKey($key)) { $test = $bTests[$key] }
        else { throw "Expected skip is missing from both shards: $key" }
        if ($test.Result -ne 'Skipped') { throw "Expected skip ran: $key" }
    }
    "Verified: shard A=$($aTests.Count), shard B=$($bTests.Count), total=$($aTests.Count + $bTests.Count); class assignments disjoint, expected skips=$($allowed.Count)."
    return
}

$fastTests=Read-Tests $Fast
$integrationTests=Read-Tests $Integration
$fullTests=Read-Tests $Full
$union=[Collections.Generic.HashSet[string]]::new($fastTests.Keys,[StringComparer]::Ordinal)
$intersection=[Collections.Generic.HashSet[string]]::new($fastTests.Keys,[StringComparer]::Ordinal)
$intersection.IntersectWith($integrationTests.Keys)
if ($intersection.Count) { throw "Overlapping tests: $($intersection -join ', ')" }
$union.UnionWith($integrationTests.Keys)
if (!$union.SetEquals($fullTests.Keys)) { throw 'Partition differs from full suite (missing or extra identities)' }
foreach ($key in $union) {
    $partition=if ($fastTests.ContainsKey($key)) {$fastTests[$key]} else {$integrationTests[$key]}
    if ($partition.Result -ne $fullTests[$key].Result -or $partition.Reason -ne $fullTests[$key].Reason) { throw "Outcome differs across profiles: $key" }
}
$beforeTests=if ($Before) { Read-Tests $Before } else { [Collections.Generic.Dictionary[string,object]]::new([StringComparer]::Ordinal) }
foreach ($key in $beforeTests.Keys) {
    if (!$fullTests.ContainsKey($key)) { throw "An existing test disappeared from full: $key" }
    if ($beforeTests[$key].Result -eq 'Passed' -and $fullTests[$key].Result -ne 'Passed') { throw "Existing passing test became skipped: $key" }
}
$summary=[ordered]@{
    Fast=$fastTests.Count; Integration=$integrationTests.Count; Full=$fullTests.Count; Before=$beforeTests.Count
    Added=@($fullTests.Keys | Where-Object { !$beforeTests.ContainsKey($_) } | Sort-Object)
    Skips=[ordered]@{}
    IdentityPolicy='Qualified suite + exact XML testcase name, including parameter/dynamic identifiers. Duplicate identities are rejected, never collapsed. Skipped identities remain in every set comparison.'
}
foreach ($scope in @('Fast','Integration','Full','Before')) {
    $inventory=switch ($scope) {Fast {$fastTests}; Integration {$integrationTests}; Full {$fullTests}; Before {$beforeTests}}
    $summary.Skips[$scope]=@($inventory.Values | Where-Object Result -eq 'Skipped' | Sort-Object Suite,Method)
}
$parent=Split-Path $Output
if ($parent) { New-Item -ItemType Directory -Force $parent | Out-Null }
$summary | ConvertTo-Json -Depth 7 | Set-Content -LiteralPath $Output -Encoding utf8
"Verified: fast=$($fastTests.Count) + integration/system=$($integrationTests.Count) = full=$($fullTests.Count); baseline=$($beforeTests.Count), added=$($summary.Added.Count); no overlap or missing identities, expected skips verified."
