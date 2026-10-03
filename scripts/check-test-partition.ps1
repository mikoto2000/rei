param(
    [Parameter(Mandatory)][string]$Fast,
    [Parameter(Mandatory)][string]$Integration,
    [Parameter(Mandatory)][string]$Full,
    [string]$Before = ''
)
$ErrorActionPreference = 'Stop'
function Read-Tests([string]$Directory) {
    $tests = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $files = @(Get-ChildItem -LiteralPath $Directory -Filter 'TEST-*.xml')
    if (!$files.Count) { throw "No XML reports: $Directory" }
    foreach ($file in $files) {
        [xml]$xml = Get-Content -Raw -LiteralPath $file.FullName
        if ([int]$xml.testsuite.failures -or [int]$xml.testsuite.errors -or [int]$xml.testsuite.skipped) {
            throw "Failed or skipped tests: $($file.Name)"
        }
        foreach ($case in $xml.testsuite.testcase) {
            # jqwik can emit only the simple testcase classname. The suite name is qualified.
            $key = "$($xml.testsuite.name)::$($case.name)"
            if (!$tests.Add($key)) { throw "Duplicate test identity: $key" }
        }
    }
    return ,$tests
}
$fastTests = Read-Tests $Fast
$integrationTests = Read-Tests $Integration
$fullTests = Read-Tests $Full
$intersection = [Collections.Generic.HashSet[string]]::new($fastTests)
$intersection.IntersectWith($integrationTests)
if ($intersection.Count) { throw "Overlapping tests: $($intersection -join ', ')" }
$union = [Collections.Generic.HashSet[string]]::new($fastTests)
$union.UnionWith($integrationTests)
if (!$union.SetEquals($fullTests)) {
    $missing = [Collections.Generic.HashSet[string]]::new($fullTests)
    $missing.ExceptWith($union)
    throw "Partition differs from full suite. Missing: $($missing -join ', ')"
}
if ($Before) {
    $beforeTests = Read-Tests $Before
    if (!$beforeTests.IsSubsetOf($fullTests)) { throw 'An existing test disappeared from the full suite' }
}
"Verified: fast=$($fastTests.Count) + integration/system=$($integrationTests.Count) = full=$($fullTests.Count); no overlap or missing existing tests."
