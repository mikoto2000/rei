# 音声入力 Phase 3: 承認付きモデル管理

> この文書の base INT8・取得ID・時間上限・実機結果は当時の記録です。現在のモデルと取得・移行手順は [Whisper turbo FP32 移行](voice-input-turbo-fp32.md) を参照してください。

Phase 2 は PR #56（45cded4acb6453fd21cf4b5a595a7a2c54ff1ee8）でmainへ統合済みです。このmainを起点に実装しています。Java 25、Windows x64、CPU、DRY (VT-4) を維持します。

## 利用方法

通常起動はモデル取得・JNIロード・マイク開始を行いません。モデル不足/破損時の `/voice on` または `/voice test` は配布元・ライセンス・サイズ・固定SHAを表示し、受付を開始しません。

~~~text
/voice models info
/voice models install --approve sherpa-1_13_8-whisper-base-bb53ee20-silero-9e2449e1
/voice models status
/voice models cancel
/voice devices
/voice device set <一覧のID>
/voice on
/voice off
~~~

installの `--approve` はinfoで表示した固定manifest IDとの完全一致が必要です。省略・別IDでは通信も保存領域の変更も行いません。音声からCLIコマンドを実行する経路はありません。モデル取得時の承認は会話のTool実行承認とは別で、既存の会話・実行権限を変えません。
取得は非同期で、status/cancel、テキスト会話を使えます。取得中のマイク開始は拒否します。取得完了後も自動でマイクを開かず、準備できた時にonを実行します。
失敗後は同じ承認付きinstallで再試行できます。自動再試行は各ファイル最大3回、待機250/500ms、取引全体の上限15分です。整合性のないデータは有効化しません。

## 固定一式とライセンス

VoiceModelManifest.pinned() は既存Phase 2で検証済みの6ファイル、169,717,680 bytes（約161.86 MiB）を使います。
Whisper encoder/decoder/tokensは同じ固定revision bb53ee204431c90d314c1cc08d28d23e5b7927ccのbase multilingual INT8です。sherpa Java/JNIはv1.13.8、Sileroは固定SHA 9e2449e1... を要求します。Sileroのrelease/asr-models URL自体は可変ですが、更新された異なる内容を受け入れません。各URL・SHA・サイズはmanifestとinfo出力で確認できます。

- [sherpa-onnx v1.13.8](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/LICENSE): Apache-2.0。
- [ONNX Runtime](https://github.com/microsoft/onnxruntime/blob/main/LICENSE): MIT。
- [Whisper上流](https://github.com/openai/whisper/blob/main/LICENSE): MIT。
- [Silero VAD](https://github.com/snakers4/silero-vad/blob/master/LICENSE): MIT。
- [変換済みWhisperの固定配布revision](https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base/tree/bb53ee204431c90d314c1cc08d28d23e5b7927cc)には独立したLICENSEファイルがありません。Whisperは上流のMITを表示し、変換者が別条件を宣言したものとして扱いません。本PRはモデル/JARの再配布を行わず、利用者の明示承認後に指定配布元から取得します。バイナリをアプリ配布物へ同梱する場合は依存ライセンス/NOTICEと再配布条件の確認を別途行います。

モデルの更新は明示的なmanifest更新で行い、アプリ起動・アプリ更新で暗黙にモデルを置き換えません。任意URLやONNXファイルをCLIから組み合わせる機能はありません。

## 保存・検証・有効化

`rei.voice.bundle-directory` が保存領域です（既定はReiデータディレクトリのvoice）。

~~~text
voice/
  staging/download-<ランダム値>/  # ダウンロード途中。推論対象にしない
  managed/<manifest-id>/          # 全体検証後、同一filesystemのatomic moveで有効化
  retained/<manifest-id>-<UUID>/  # 復旧時に退避した旧一式。自動削除しない
~~~

VoiceModelManagerは単一の取得ワーカー、取消トークン、期限用ワーカーを使います。各ファイルの正確なサイズ/SHA-256、さらに全一式を確認してからディレクトリ単位で有効化します。atomic moveが使えない場合は失敗し、非atomicコピーで代用しません。旧一式の退避後に切り替えが失敗した場合は旧ディレクトリを戻します。取消は有効化と同じ排他制御で判定し、受け付けた取消後に不完全な一式を有効化しません。

HttpsVoiceAssetTransportはJVM既定の証明書検証を使用し、HTTPS限定、認証情報付きURL拒否、最大5回のHTTPSリダイレクト、接続15秒・リクエスト5分の期限を設定します。Content-Lengthと実際の読み取りの両方で固定サイズを確認し、サイズ超過を保存しません。取消で読み取り中のHTTP bodyを閉じ、所有ワーカーの割込みでヘッダー待ちも取り消します。固定JARをそのまま取得し、モデル管理はアーカイブ展開をしません。

相対パスの絶対指定・..・バックスラッシュ・ドライブ名・重複を拒否し、保存/検証時のsymlinkを拒否します。stageは保存領域内に限定し、取消/失敗時は自身のstageだけを削除します。正常終了でもstageは残りません。進捗のJLine通知は約1秒ごとに抑え、URLの署名付きリダイレクト先や音声/認識文はモデル進捗へ出しません。

再利用時もサイズ/SHAを検証します。rootに従来形式（jvm.jar/native.jar/models/...）で配置した完全な手動一式も同じmanifestで検証して利用できます。managedの完全一式を優先し、破損ファイルや一部だけの手動配置を混ぜません。古いmanifestディレクトリは残します。

## 責務と検証

- VoiceModelManifest: 固定配布情報、互換一式、パス・サイズ・SHA検査。
- VoiceAssetTransport/HttpsVoiceAssetTransport: HTTPSストリーム取得、上限、取消。
- VoiceModelManager: 承認・非同期取引・再試行・検証・切り替え・offline再利用。
- VoiceCommand.Models: info/install/status/cancel、on/test時の不足案内。
- VoiceConfiguration/SherpaBackendFactory: 検証済みディレクトリの遅延利用。通常起動でJNIをロードしない。
- VoiceEventPublisher/VoiceShellEventRenderer: 既存JLine経路の状態・進捗表示。

TDDでモデル管理10件、HTTPS取得4件、コマンド接続3件を追加しました。未実装のRed、Green、実機で判明したキャッシュ状態表示と、その後の破損表示のRed/Greenを確認しました。関連53件はfailures 0、errors 0、skip 0で成功しました。検証済み一式はREADY、破損した一式はCORRUPTと表示します。通常回帰にネットワーク取得や録音は含めません。

2026-10-09に空の専用target領域への実HTTPS取得、6ファイル全ての検証、atomic有効化、取得した一式で日本語WAVのVAD/Whisper認識・解放3回が成功しました。別JVMではネットワーク取得を例外にするtransportを使い、同じ一式の検証と日本語認識3回、指定DRY (VT-4)で約1秒のLISTENING→OFFを確認しました。マイク試験は診断モードでAgentへ送信せず、録音ファイルを保存しません。

再現用 `poc/voice/VoiceModelsAcceptance.java` はROOT、download/offline、承認manifest ID、日本語WAV、任意の完全一致マイク名を受け取ります。target/classesへ通常のMavenコンパイルを行ってから、同ファイルをjavacでtargetの専用classesへコンパイルし、Javaの `--enable-native-access=ALL-UNNAMED` と両classesのclasspathで実行します。downloadは空のmanaged領域のみ、offlineはネットワーク取得を禁止します。日本語WAVはPhase 0の合成音声fixtureで、自然発話精度評価ではありません。

全回帰・CI・レビュー・main統合の結果は本PhaseのPRに記録します。Phase 4以降はmain統合後に進みます。モデル比較/自然発話精度、高度機能、会話スタイルは未完了です。