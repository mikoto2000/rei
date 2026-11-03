$ErrorActionPreference = 'SilentlyContinue'
$root = 'F:\ai-image'

Write-Output '=== top dirs ==='
(Get-ChildItem -LiteralPath $root -Directory).Name -join ', '
Write-Output '=== top files ==='
(Get-ChildItem -LiteralPath $root -File).Name -join ', '

Write-Output '=== models ==='
Get-ChildItem -LiteralPath (Join-Path $root 'models') -Directory | ForEach-Object {
    $c = (Get-ChildItem -LiteralPath $_.FullName -File -Recurse).Count
    Write-Output ($_.Name + ' : ' + $c + ' files')
}

Write-Output '=== output ==='
Get-ChildItem -LiteralPath (Join-Path $root 'output') -Directory | ForEach-Object {
    $c = (Get-ChildItem -LiteralPath $_.FullName -File -Recurse).Count
    Write-Output ($_.Name + ' : ' + $c + ' files')
}

Write-Output '=== scripts ==='
(Get-ChildItem -LiteralPath (Join-Path $root 'scripts') -File).Name -join ', '
Write-Output '=== prompts ==='
(Get-ChildItem -LiteralPath (Join-Path $root 'prompts') -File).Name -join ', '
Write-Output '=== workflows ==='
(Get-ChildItem -LiteralPath (Join-Path $root 'workflows') -File).Name -join ', '

Write-Output '=== python / torch ==='
$py = Join-Path $root 'venv\Scripts\python.exe'
$pyScript = Join-Path $root '_probe_py.py'
& $py $pyScript

Write-Output '=== comfyui git ==='
git -C (Join-Path $root 'ComfyUI') log -1 --format='%H %ci'

Write-Output '=== comfyui version ==='
$vf = Join-Path $root 'ComfyUI\comfyui_version.py'
if (Test-Path -LiteralPath $vf) { Get-Content -LiteralPath $vf }

Write-Output '=== free space F (GB) ==='
[math]::Round((Get-PSDrive F).Free / 1GB, 1)

Write-Output '=== port 8188 listen ==='
(Get-NetTCPConnection -LocalPort 8188 -State Listen).Count

Write-Output '=== custom_nodes ==='
(Get-ChildItem -LiteralPath (Join-Path $root 'ComfyUI\custom_nodes') -Directory).Name -join ', '
