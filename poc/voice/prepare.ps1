param([switch]$Download)
$ErrorActionPreference = 'Stop'
$root = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$cache = Join-Path $root 'target/voice-poc'
$models = Join-Path $cache 'models'
New-Item -ItemType Directory -Force $models | Out-Null
$revision = 'bb53ee204431c90d314c1cc08d28d23e5b7927cc'
$files = @(
 @('jvm.jar','https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-jvm-1.13.8.jar','77b7b047fade4eadada96b568eb92615049aaf1dc317c7244e46c1ea38b9a63b'),
 @('native.jar','https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-native-lib-win-x64-1.13.8.jar','33fbdbd5410e9ba9bdda94aa164ec8f7825bb49246420d8ce9bdd88219d97039'),
 @('models/base-encoder.int8.onnx',"https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base/resolve/$revision/base-encoder.int8.onnx",'0b8fb1304b6109976038efff5ace81720e00386f3ff6b54ee8c75291ca0a1e11'),
 @('models/base-decoder.int8.onnx',"https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base/resolve/$revision/base-decoder.int8.onnx",'9759d217388a01b3a4c7c15533201067b48ae819c4daafc8624e64b9409dc02d'),
 @('models/base-tokens.txt',"https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base/resolve/$revision/base-tokens.txt",'b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126'),
 @('models/silero_vad.onnx','https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx','9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6')
)
foreach($file in $files) {
 $path = Join-Path $cache $file[0]
 if(-not (Test-Path -LiteralPath $path)) {
  if(-not $Download) { throw "Missing $($file[0]); rerun with -Download after reading docs/voice-input-phase0.md" }
  $staging = "$path.partial"
  Invoke-WebRequest $file[1] -OutFile $staging
  if((Get-FileHash -LiteralPath $staging -Algorithm SHA256).Hash.ToLowerInvariant() -ne $file[2]) { throw "SHA mismatch: $($file[0]); partial file is not activated" }
  Move-Item -LiteralPath $staging -Destination $path -Force
 }
 if((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() -ne $file[2]) { throw "SHA mismatch: $($file[0])" }
}
$classes=Join-Path $cache 'classes'
New-Item -ItemType Directory -Force $classes | Out-Null
& javac -encoding UTF-8 --release 17 -classpath (Join-Path $cache 'jvm.jar') -d $classes (Join-Path $PSScriptRoot 'Pcm.java') (Join-Path $PSScriptRoot 'PcmTest.java') (Join-Path $PSScriptRoot 'VoicePoc.java')
if($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& java -cp $classes PcmTest
exit $LASTEXITCODE
