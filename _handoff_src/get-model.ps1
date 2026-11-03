$ErrorActionPreference = 'Stop'

<#
.SYNOPSIS
モデルファイルを F:\ai-image\models 配下の適切な場所へダウンロードする。

.EXAMPLE
.\get-model.ps1 -Url 'https://civitai.com/api/download/models/123456' -Kind checkpoints -FileName 'illustrious-v1.safetensors'

.EXAMPLE
.\get-model.ps1 -Url 'https://...' -Kind loras -FileName 'char.safetensors' -Sha256 'ABCDEF...'
#>

param(
    [Parameter(Mandatory)]
    [ValidateNotNullOrEmpty()]
    [string] $Url,

    [Parameter(Mandatory)]
    [ValidateSet('checkpoints','loras','vae','controlnet','upscale_models','embeddings')]
    [string] $Kind,

    [Parameter(Mandatory)]
    [ValidateNotNullOrEmpty()]
    [string] $FileName,

    [string] $Sha256,

    [string] $Token
)

$root = 'F:\ai-image'
$destDir = Join-Path $root ('models\' + $Kind)

if (-not (Test-Path -LiteralPath $destDir)) {
    New-Item -ItemType Directory -Path $destDir -Force | Out-Null
}

$dest = Join-Path $destDir $FileName

if (Test-Path -LiteralPath $dest) {
    Write-Output ('already exists: ' + $dest)
    Write-Output ('size = ' + [math]::Round((Get-Item -LiteralPath $dest).Length / 1MB, 1) + ' MB')
    return
}

$tmp = $dest + '.part'

$headers = @{}
if (-not [string]::IsNullOrWhiteSpace($Token)) {
    $headers['Authorization'] = 'Bearer ' + $Token
}

Write-Output ('downloading: ' + $Url)
Write-Output ('to         : ' + $tmp)

$params = @{
    Uri         = $Url
    OutFile     = $tmp
    UserAgent   = 'Mozilla/5.0'
    ErrorAction = 'Stop'
}
if ($headers.Count -gt 0) { $params.Headers = $headers }

$sw = [System.Diagnostics.Stopwatch]::StartNew()
Invoke-WebRequest @params
$sw.Stop()

$sizeMB = [math]::Round((Get-Item -LiteralPath $tmp).Length / 1MB, 1)
Write-Output ('downloaded: ' + $sizeMB + ' MB in ' + [math]::Round($sw.Elapsed.TotalSeconds, 1) + ' s')

if (-not [string]::IsNullOrWhiteSpace($Sha256)) {
    Write-Output 'verifying sha256...'
    $actual = (Get-FileHash -LiteralPath $tmp -Algorithm SHA256).Hash
    if ($actual -ne $Sha256.ToUpperInvariant()) {
        Remove-Item -LiteralPath $tmp -Force
        throw ('sha256 mismatch. expected=' + $Sha256 + ' actual=' + $actual)
    }
    Write-Output 'sha256 OK'
} else {
    Write-Output 'sha256 not provided; skipped verification'
}

Move-Item -LiteralPath $tmp -Destination $dest -Force
Write-Output ('saved: ' + $dest)
