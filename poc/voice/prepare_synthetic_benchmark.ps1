param([string]$Output='target/voice-phase5-fixtures')
$ErrorActionPreference='Stop'
New-Item -ItemType Directory -Force $Output | Out-Null
$taskVoice=New-Object -ComObject SAPI.SpVoice
$taskToken=@($taskVoice.GetVoices() | Where-Object {$_.GetDescription() -match 'Haruka'})[0]
if($null -eq $taskToken){throw 'Microsoft Haruka is required; do not substitute another voice silently'}
$taskVoice.Voice=$taskToken
$taskFixtures=@(
 @('synthetic-short.wav','こんにちは。短く挨拶してください。','short-question'),
 @('synthetic-long.wav','音声入力の実装を確認してください。テストを実行し、失敗した原因を調べて、必要な修正を行ってください。その後で変更点と確認結果を短く報告してください。','long-instruction'),
 @('synthetic-technical.wav','JavaとSpring Bootを使います。Whisperの認識結果をGatewayに渡し、GitHubにプルリクエストを作成してください。','mixed-technical'),
 @('synthetic-files.wav','pom.xmlとREADME.mdを確認して、srcディレクトリにあるVoiceCommand.javaを修正してください。','filenames'),
 @('synthetic-first.wav','変更内容を確認してください。','pause-first'),
 @('synthetic-second.wav','それから、回帰テストを実行してください。','pause-second'),
 @('synthetic-commands.wav','git status、mvn test、git diff を実行してください。','command-terms'))
$taskMeta=@()
foreach($taskFixture in $taskFixtures){
 $taskStream=New-Object -ComObject SAPI.SpFileStream;$taskStream.Format.Type=18
 $taskDest=[IO.Path]::GetFullPath((Join-Path $Output $taskFixture[0]))
 try{$taskStream.Open($taskDest,3,$false);$taskVoice.AudioOutputStream=$taskStream;[void]$taskVoice.Speak($taskFixture[1])}finally{$taskStream.Close()}
 $taskMeta+=[pscustomobject]@{file=$taskFixture[0];reference=$taskFixture[1];category=$taskFixture[2];source=$taskToken.GetDescription()+' synthetic';sha256=(Get-FileHash $taskDest).Hash.ToLowerInvariant()}
}
$taskMeta | ConvertTo-Json -Depth 3 | Set-Content -Encoding utf8 (Join-Path $Output 'synthetic.json')