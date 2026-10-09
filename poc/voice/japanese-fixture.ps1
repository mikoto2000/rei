# Optional deterministic local test fixture. Does not record a microphone or use a cloud service.
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.Speech
$root=Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$directory=Join-Path $root 'target/voice-poc/models'
New-Item -ItemType Directory -Force $directory | Out-Null
$synth=New-Object System.Speech.Synthesis.SpeechSynthesizer
try {
 $synth.SelectVoice('Microsoft Haruka Desktop')
 $format=New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(16000,[System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen,[System.Speech.AudioFormat.AudioChannel]::Mono)
 $synth.SetOutputToWaveFile((Join-Path $directory 'japanese.wav'),$format)
 $synth.Speak('こんにちは。今日は音声入力の動作を確認します。日本語の文章を認識してください。')
} finally {$synth.Dispose()}
