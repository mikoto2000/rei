$ErrorActionPreference = 'SilentlyContinue'
$root = 'F:\ai-image'
$comfy = Join-Path $root 'ComfyUI'

Write-Output '=== extra_model_paths.yaml in ComfyUI ==='
$y = Join-Path $comfy 'extra_model_paths.yaml'
if (Test-Path -LiteralPath $y) {
    Write-Output ('exists, bytes=' + (Get-Item -LiteralPath $y).Length)
    $bytes = [System.IO.File]::ReadAllBytes($y)
    Write-Output ('first3=' + ($bytes[0..2] -join ','))
} else {
    Write-Output 'MISSING'
}

Write-Output '=== key pip packages ==='
$py = Join-Path $root 'venv\Scripts\python.exe'
& $py -m pip list --disable-pip-version-check 2>$null | Select-String -Pattern '^(torch|torchvision|torchaudio|numpy|pillow|safetensors|transformers|opencv|aiohttp|comfyui)'

Write-Output '=== comfyui requirements.txt exists ==='
if (Test-Path -LiteralPath (Join-Path $comfy 'requirements.txt')) { Write-Output 'yes' } else { Write-Output 'no' }

Write-Output '=== venv size GB ==='
[math]::Round(((Get-ChildItem -LiteralPath (Join-Path $root 'venv') -Recurse -File | Measure-Object -Property Length -Sum).Sum / 1GB), 2)

Write-Output '=== ComfyUI size MB ==='
[math]::Round(((Get-ChildItem -LiteralPath $comfy -Recurse -File | Measure-Object -Property Length -Sum).Sum / 1MB), 1)

Write-Output '=== leftover temp scripts ==='
(Get-ChildItem -LiteralPath $root -File -Filter '_probe*.ps1').Name -join ', '
(Get-ChildItem -LiteralPath $root -File -Filter '_setup*.ps1').Name -join ', '

Write-Output '=== git status ComfyUI ==='
git -C $comfy status --porcelain

Write-Output '=== python exe version ==='
& $py --version
