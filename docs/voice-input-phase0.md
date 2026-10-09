# 音声入力 Phase 0 — 調査・PoC（PoC検証済み・統合待ち）

## 開始条件と現状

2026-10-09、先行 Web 検索改善 PR #42–47、作業完遂能力向上 PR #48–52 の merged/base=main と remote main `462b8ee1002b13eb2f040079922cf3331b160faf` を確認。先行チャットの Phase 0–4 完了報告も確認して開始条件監視を停止した。

本番の音声入力・CLI コマンド・会話スタイルは未実装。Phase 0 は隔離 PoC と設計のみ。Java 17 指定に対し現在のアプリは Java 25 / Spring Boot 4.1.1 / Spring AI 2.0.1。2026-10-09にユーザーがJava 25維持を承認した。以降はJava 25を対象とする。アプリの Java バージョン・依存は変更していない。PoC の release 17 コンパイルは成功したが Java 17 JVM での実行は未検証。

## 既存経路と変更予定

| 責務 | 現在の実クラス / 経路 | 後続の変更方針 |
|---|---|---|
| キーボードとコマンド | ReiApplication → ActiveRunPrompt.readLine → UserInputService → Picocli ChatCommand | スラッシュコマンド解釈はキーボードのまま、会話入力だけ共通 admission へ |
| 会話受付 / Session | ChatCommand → ShellConversationService.submit → SessionLifecycle.submit | Shell Client の Project/Session を受付時に固定。録音スレッドに ThreadLocal の選択を暗黙継承しない |
| 順序と実行 | ConversationInputRouter / core.chat.ProjectRunQueue → ChatExecutionService | 既存 FIFO と64/project・256/totalの上限、cancelQueuedを維持。voice保留3件・入力ID重複防止を共通admissionに追加し、音声だけ別 ChatClient を作らない |
| 追加入力 | ShellConversationService.intervene → ConversationInputRouter.offerIntervention → UserInterventionQueue | 音声は初期既定で新規会話保留。割り込みは明示 opt-in・所有者/Session一致 |
| 出力 | ShellEventSession / ShellAgentEventRenderer → JLineShellEventOutput.printAbove | 音声状態も同じ同期出力へ。読みかけ文字列を直接 System.out で壊さない |
| Agent / 検証 | ChatExecutionService / RunExecutionContext / ProgressEvaluator / GoalLoopService / FileGoalVerifier / GoalCompletionGate | 完了・予算・修復・Checkpoint判定を維持 |
| Tool / 承認 | ToolPermissionGuard / ToolPermissionPolicy / CommandCancellationService | Voice を承認の代替とせず同じPolicyへ。音声由来スラッシュコマンドは実行禁止 |
| Native / HTTP | SessionController / RunService / RunController | 既存Session lifecycleと所有境界を共有し、Native経路を複製しない |
| 起動・終了 | ReiApplication / ApplicationShutdownNotifier | 音声Coordinatorを明示close、取得line停止、worker取消、JNI release |
| 設定・補完 | application.yaml / 外部テンプレート / ReiLineReaderFactory / CompletionEngine | voice既定OFF、音声コマンドとヘルプをPicocliへ追加 |

Phase 1 は immutable inputId/source/project/session/text/time と有限の重複 ledger・保留キューを追加する。音声のSession変更時に保留入力を新Sessionへ付け替えない。録音、VAD、segment queue（2）、ASR worker（1）、conversation pending（3）を分離し、録音をモデル/LLM待ちで停止させない。

Phase 2 では Java Sound capture / format conversion、VAD / recognizer interfaces、coordinator、events を作る。JNIクラスをアプリ全体の起動時にロードせず、voice on時の失敗をCLIへ報告する。VAD単独の1200ms確定を基準にし二重の無音タイマーを足さない。最大25秒の強制区切りは通常確定と区別し送信しない。語頭pre-roll・末尾余白・short readの扱いは仮想フレームのTDDで別途保証する。

Phase 7 の既存 AgentRunContext.Mode.CONVERSATION は並行実行の権限/排他モードであり、今回の応答スタイルとは別概念。これを会話スタイル用に上書きせず、Sessionの応答スタイルを追加してTool・Planning Loopを維持する。

## 技術選定

sherpa-onnx v1.13.8 は JavaのOfflineRecognizer / Vad API とWindows x64用Java/JNI JARが同一リリースで配布される。release JARのSHA-256をGitHub asset digestと照合した。Java25/Windows x64実マイクの統合動作を確認したためCPU版sherpa-onnx v1.13.8を後続実装の採用候補として選定する。JitPackへの依存だけで再現性を保証せず、固定JARの取り込みと配布物検証を後続で設計する。

ONNX Runtime JavaはMaven CentralとWindows x64 CPU/GPUを提供するが、汎用tensor/session APIだけではWhisperの音響前処理、token decode、cache loopをアプリで管理する必要がある。保守負担の大きい自前decoderを避けるためsherpaを第一候補とする。ORT直接実装の実性能比較・GPU試験は未実施。

ライセンスは上流の [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/LICENSE)（Apache-2.0）、[ONNX Runtime](https://github.com/microsoft/onnxruntime/blob/main/LICENSE)、[Whisper](https://github.com/openai/whisper/blob/main/LICENSE)、[Silero VAD](https://github.com/snakers4/silero-vad/blob/master/LICENSE)（後3件はMIT）を確認した。変換モデルの再配布条件と依存ライセンス/NOTICEは配布統合時にも確認し、本PRにはJAR・モデル・音声を含めない。

- [公式Java VAD + Whisper例](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/java-api-examples/VadFromMicWithNonStreamingWhisper.java)
- [固定Java/JNI release](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.8)
- [Whisper ONNX一式](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/whisper/export-onnx.html)
- [ORT Java](https://onnxruntime.ai/docs/get-started/with-java.html)
- [Java Sound capture](https://docs.oracle.com/javase/tutorial/sound/capturing.html)

公式マイク例のsigned low byte加算はPCM16変換を壊す。short readにも古いbuffer値が入る。PoC Pcm.decodeはunsigned low byte・実read長・偶数長を検証する。公式例の録音スレッド上のASR呼出しも本番には採用しない。

## PoC 再現手順

本番Mavenに影響しない `poc/voice`。Windows x64 JDK、ネットワーク取得は明示 `-Download` のみ。モデル一式約161MBとJAR約8.5MB。HTTPS・固定Whisper revision・全SHA照合を使用。Sileroは可変release URLだが固定SHAの不一致で拒否する。PoC手順はPhase 3の非同期/取消/全体atomicモデル管理の実装ではない。

```powershell
./poc/voice/prepare.ps1 -Download
# Optional: Windows に Microsoft Haruka Desktop がインストール済みの場合
./poc/voice/japanese-fixture.ps1
$cp = 'target/voice-poc/classes;target/voice-poc/jvm.jar;target/voice-poc/native.jar'
java --enable-native-access=ALL-UNNAMED -cp $cp VoicePoc devices
java --enable-native-access=ALL-UNNAMED '-Dstdout.encoding=UTF-8' -cp $cp VoicePoc target/voice-poc/models path/to/japanese.wav
```

WAVは最大60秒、Java Soundで16kHz signed PCM16 monoへ変換。VAD 0.5 / window512 / speech400ms / silence1200ms、CPU1thread、Whisper base multilingual INT8 / ja transcribe。VADは最後にflushするため、これはストリーミング無音確定レイテンシの受入試験ではない。最大発話時間の自動送信禁止など本番安全条件は後続で実装する。取得音声の永続保存・既存Agentへの送信は行わない。WAVモードはローカル音声ファイルを読んで認識結果を表示する。明示 `mic MODEL_DIR EXACT_DEVICE_NAME` はPCM16対応の同名が一つだけの場合に5秒の準備待ち後に20秒取得する。タイマーでlineをcloseし録音上限も640000 bytesとする。取得後のVAD/ASRは同期であり、連続ハンズフリーの本番パイプラインではない。実行前に利用者が対象機器を選択する。

prepare.ps1 に固定URLとSHAを記録した。Whisper revision `bb53ee204431c90d314c1cc08d28d23e5b7927cc` のencoder/decoder/tokensを混在させない。不正JAR/モデルは利用しない。ローカル手動配置でもSHA照合は必須。

## 実行結果と残存リスク

環境: Windows x64 / OpenJDK25 build25+36-3489。ユーザー承認により対象はJava25。PoCはrelease17 compileも成功したがJava17 JVM上の実行は未検証。

- PCM変換: 未実装compile Red→Green。signed低byte・符号端点、奇数長拒否、short readの3境界。
- 合成日本語WAV: Microsoft Haruka Desktopによる「こんにちは。今日は音声入力の動作を確認します。日本語の文章を認識してください。」を3起動/解放で認識。「こんにちは 今日は音声入力の動作を確認します 日本語の文を認識してください」、1segment=10.160秒、decode844.4 / 853.7 / 870.9ms。
- 日本語モデルパス: 3起動/解放成功、decode1140.6 / 1080.6 / 1081.4ms。JNI resourceはfinallyで明示release。
- 指定実マイク `DRY (VT-4)`: 準備5秒後に20秒メモリ内取得。319500 samples / 19.969秒、nonzero279843、peak0.721222 / RMS0.095711。3segment（3.744 / 8.288 / 5.776秒）すべてで「こんにちは、音声入力の動作を確認します。」を全文認識。decode585.1 / 689.2 / 568.0ms、終了0。録音ファイルは作成せずAgentにも送信しない。
- 初期実機試験の失敗履歴: 10秒取得で2回VAD未検出。その後は0.992秒だけ検出し「認します」と部分認識した。ユーザーは全文発話したと確認。開始音を加えた試験は全10区間peak0.000031 / RMS0.000015でほぼ無音、VAD未検出。開始音は聞こえなかったため廃止。準備待ちと取得時間拡大後には全文認識できたが、過去の失敗原因自体は未確定。
- JNI欠落: native JARをclasspathから除くとUnsatisfiedLinkErrorで明示失敗。PoCの障害確認であり本番CLI継続の保証ではない。
- 既存Java回帰: 4088件、failure0 / error0 / skipped1（既存PlantUML条件）、BUILD SUCCESS、既存20分wrapper終了0。PoCは通常単体テストにネットワークやモデル取得を追加しない。

少数の合成・実発話試験であり、自然発話CER、P50/P95、CPU使用率、長時間リーク耐性、RTF目標達成は未検証。実機経路の実現性確認と、本番での連続ハンズフリー・無音確定・非同期入力の受入試験を混同しない。後者はPhase2以降で検証する。
## 後続の依存・配布統合方針

本番組み込み時はJNIをインターフェース実装内に隔離し、voice有効化時だけロードする。固定releaseのJava/JNI JARをSHA照合後にビルド用領域へ取得し、アプリ配布物に依存JARを同梱する方式を第一案とする。手動install-fileだけに依存する開発者固有ビルドにはしない。ビルドの再現と同梱した配布物のWindows起動を組み込みPhaseで検証する。モデルはアプリJARに同梱せずPhase3で同一manifest一式を管理する。必要ライセンス・NOTICEを配布物に含める。CPU版を初期対象としGPU未検証をCPU対応と混同しない。

Phase0の実機PoC確認は完了したが、PRのCI・レビュー・main統合は未完了。後続Phaseはmain統合後に開始する。
