$ErrorActionPreference = 'Stop'
$output = 'target/regression-tests'
New-Item -ItemType Directory -Path $output -Force | Out-Null
foreach ($file in Get-ChildItem -LiteralPath target/surefire-reports -Filter 'TEST-*.xml') {
    $settings = [Xml.XmlReaderSettings]::new()
    $settings.DtdProcessing = [Xml.DtdProcessing]::Prohibit
    $settings.XmlResolver = $null
    $reader = [Xml.XmlReader]::Create($file.FullName, $settings)
    try {
        $xml = [Xml.XmlDocument]::new()
        $xml.Load($reader)
    } finally {
        $reader.Dispose()
    }
    foreach ($node in $xml.SelectNodes('//properties | //system-out | //system-err')) {
        [void]$node.ParentNode.RemoveChild($node)
    }
    foreach ($node in $xml.SelectNodes('//failure | //error')) {
        $node.RemoveAll()
    }
    foreach ($node in $xml.SelectNodes('//skipped')) {
        if (!$node.GetAttribute('message')) {
            $reason = ($node.InnerText.Trim() -split '\r?\n')[0].Trim() -replace '^org\.opentest4j\.TestAbortedException:\s*',''
            $node.SetAttribute('message', $reason)
        }
        $node.InnerText = ''
    }
    $xml.Save((Join-Path (Resolve-Path $output) $file.Name))
}
