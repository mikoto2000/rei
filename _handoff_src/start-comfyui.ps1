$ErrorActionPreference = 'Stop'
$root = 'F:\ai-image'
$py = Join-Path $root 'venv\Scripts\python.exe'
$main = Join-Path $root 'ComfyUI\main.py'

if (-not (Test-Path -LiteralPath $py)) { throw ('venv python not found: ' + $py) }
if (-not (Test-Path -LiteralPath $main)) { throw ('ComfyUI main.py not found: ' + $main) }

$args = @(
    $main
    '--listen', '127.0.0.1'
    '--port', '8188'
    '--preview-method', 'auto'
    '--output-directory', (Join-Path $root 'output\raw')
    '--input-directory', (Join-Path $root 'input')
)

Write-Output ('starting: ' + $py + ' ' + ($args -join ' '))
& $py @args
Write-Output ('LASTEXITCODE = ' + $LASTEXITCODE)
