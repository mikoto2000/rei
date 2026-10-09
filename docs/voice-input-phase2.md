# 音声入力 Phase 2 — マイク/VAD/ASR パイプライン

> この文書の base INT8・取得ID・時間上限・実機結果は当時の記録です。現在のモデルと取得・移行手順は [Whisper turbo FP32 移行](voice-input-turbo-fp32.md) を参照してください。

Phase 0 は PR #53、Phase 1 は PR #54 で main に統合済みです。
本Phaseは実装・検証中です。実マイクから既存Agentの応答までの受入試験、全回帰、CI、PRレビュー/main統合が終わるまで完了扱いにしません。

## 実装

- 初期状態はOFF。Springの起動、通常チャット、デバイス一覧ではモデル/JNIを読み込まず、録音もしません。
- AudioDeviceServiceは入力可能なJavaSound Mixerの名前・説明・ベンダー・バージョンからIDを生成します。IDが重複、選択がない、切断された場合は拒否します。別マイクやOS既定入力へのフォールバックはありません。このIDは物理デバイスの永続シリアル番号ではありません。
- JavaSoundMicrophoneCaptureは選択したMixerだけを開きます。直接16kHz/mono/PCM16 LEを優先し、対応する48kHz/44.1kHz等のPCM16からJavaSoundで変換できます。短いreadを積み上げて512サンプルのフレームを作り、未完了フレームを確定発話として送信しません。
- sherpa-onnx 1.13.8のSilero VADの生確率を使用し、独自のSpeechSegmentAssemblerで無音確定を行います。ライブラリの自動分割やhangoverを重ねて使いません。
- 初期値: 閾値0.5、pre-roll 300ms、最小音声400ms、無音1200ms、最大25秒、tail 200ms。最大長に達した未完了発話は送信せず破棄し、無音が来るまで続きを抑制します。
- 録音/VAD、ASR、既存Agentは別の実行経路です。VoiceInputQueueは確定発話2件、ASRは1ワーカー。満杯時は録音を待たせず発話を破棄して通知します。既存共通ゲートウェイのVOICE待機上限3件も適用されます。
- 発話ごとにUUID、VOICE、固定したProject/Session、確定時刻を付けてShellConversationServiceへ送ります。既存のSession/Run/Agent/権限確認経路を使用します。音声認識したスラッシュコマンドは実行しません。空、記号のみ、制御文字、過大な認識結果も拒否します。
- offでは録音と認識待ちを停止します。認識が途中でも遅れて返った結果を送信しません。JNI解放は録音・認識の実スレッド終了後です。Futureのcancelだけを終了確認の代用にしません。
- EOF、VAD/ASR失敗、5秒以上フレームが返らない場合は停止し、FAILEDを表示します。JNI初期化失敗は音声機能だけを失敗させます。
- 通常の音声イベントには音声波形や認識本文を含めません。録音ファイルは保存しません。明示的な /voice test だけが認識本文を画面に表示し、Agentには送りません。

## 固定したローカルランタイム

本番コードはビルド時の手動Maven installに依存しません。音声の初回有効化時に、SHA-256/サイズ固定のJVM JAR・Windows x64 native JAR・モデル一式を検証し、専用URLClassLoaderで遅延ロードします。
全資材の値は SherpaBackendFactory.ASSETS に固定しています。ONNXのデコードは公式sherpa-onnxのWhisper OfflineRecognizerへ委譲します。

Phase 2では、Phase 0の検証済みローカルbundleを指定します。自動取得、承認表示、staging/activation、キャンセル・再試行はPhase 3で実装します。欠けたbundleを不完全なまま有効化しません。

配置:
~~~
BUNDLE/
  jvm.jar
  native.jar
  models/
    silero_vad.onnx
    base-encoder.int8.onnx
    base-decoder.int8.onnx
    base-tokens.txt
~~~

Java 25を維持します。起動時は --enable-native-access=ALL-UNNAMED を指定してください。
~~~
java --enable-native-access=ALL-UNNAMED -jar target/rei-0.0.1-SNAPSHOT.jar --rei.voice.bundle-directory=BUNDLE
~~~
既定bundleディレクトリはReiデータディレクトリ配下の voice です。
音声runtime/modelの配布元・ライセンス・固定リビジョンは [Phase 0](voice-input-phase0.md) に記載しています。大容量JAR・DLL・ONNX・録音データはリポジトリへコミットしません。

## コマンド

~~~
/voice devices
/voice device set <一覧のID>
/voice status
/voice on
/voice off
/voice test
/voice config
/voice config --silence-ms 1200 --threshold 0.5
/voice pending
/voice pending cancel <UUID>
~~~

使用マイクはユーザー指定の DRY (VT-4) です。名前の部分一致や一覧の順番で自動選択せず、devicesで表示したIDを明示選択します。device-idは rei.voice.device-id の設定でも指定できます。
configの他のオプションは --pre-roll-ms / --min-speech-ms / --max-speech-ms / --tail-ms です。
JNIを呼ぶ前にフレームの長さ・有限値・正規化範囲を検証します。マイク・タイミング設定の変更はOFF/FAILED時のみです。CLIでの変更はプロセス内の設定に反映します。
ヘルプとサブコマンド/オプション補完はPicocliの既存経路を使います。

on/testはローカル初期化完了を最大30秒待ち、LISTENING（受付中）を表示します。testは受付開始から20秒で自動停止します。開始音は前回の実機試験で聞こえなかったため、音を受付開始の根拠にしません。

## 検証と残る範囲

TDDのRed/Greenで、独自VAD確定、ノイズ・最大長破棄、PCM変換、ASR遅延時の録音継続、bounded queue、off中のJNI解放順・遅延結果破棄、初期化中のoff、切断/応答停止、スラッシュ拒否、明示デバイス選択、無効設定の原子性を確認しています。
実sherpa/JNIと日本語WAVは明示指定の SherpaBackendIT で3回の生成・認識・解放を検証しています。これは実マイク/実LLMの受入試験や自然発話の精度評価の代用ではありません。

~~~
./mvnw.cmd -B "-Dtest=Voice*Test,Speech*Test,AudioDeviceServiceTest" test
./mvnw.cmd -B "-Dtest=SherpaBackendIT" "-Drei.voice.test.bundle=BUNDLE" "-DargLine=--enable-native-access=ALL-UNNAMED" test
~~~
通常の回帰試験はモデル取得・録音を行いません。SherpaBackendITは明示的な -Dtest 指定で実行します。

sherpaのVAD JNIはdebug=falseでも初期化時の設定をnative stderrへ出力します（[固定版ソース](https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/v1.13.8/sherpa-onnx/jni/voice-activity-detector.cc)）。モデル詳細の大量debug出力は無効化し、通常の初期化はコマンドの完了前に待ちます。音声状態・ドロップ通知・診断結果はJLineShellEventOutputを使います。native異常時の直接stderr、初期化タイムアウト後のnative動作、長時間運用/繰り返しの資源測定はPhase 4の検証範囲です。

Phase 3: 承認付きモデル管理。Phase 4: 送信先変更・OS復帰・デバイス識別・訂正確認・診断の強化。Phase 5: 自然発話とtiny/base/smallの計測。Phase 6: GPU/ウェイクワード/割込み/話者/TTS/自己エコーの実現可能性と実装可能な項目。Phase 7: 応答スタイル。
### 実マイクから既存Agentまでの受入試験

poc/voice/acceptance.ps1 は、ビルド済みアプリの依存JARから手動試験用ランタイムを用意します。
専用のDataDirectoryを指定し、既存のユーザーDB・実行中アプリを試験用に流用しません。
ExternalConfigは既存のLLM設定を読み取り専用で使用します。バックグラウンド話題生成、読み上げ、MCP自動接続などを試験時に無効化し、Webサーバーも起動しません。既存サービスの @Scheduled 定期処理を受入試験プロセス内で登録しないようにします。

~~~powershell
.poc/voice/acceptance.ps1 -Bundle BUNDLE -DataDirectory TEST_DATA -ReadyFile NEW_READY_FILE -Microphone 'DRY (VT-4)' -ExternalConfig EXISTING_CONFIG
~~~

新規のReadyFileが作成されるまでマイクはOFFです。WAITING表示後、発話できる時に別のターミナルで New-Item -ItemType File -Path NEW_READY_FILE を実行します。
LISTENING表示後20秒間、「こんにちは。音声入力のテストです。短く挨拶してください。」と話します。
20秒でマイクを停止し、既存ShellConversationService/Agent経路の応答と正常終了した会話履歴を確認します。
成功時のみ ACCEPTANCE PASSED を表示します。音声波形は保存せず、通常の試験会話履歴を指定したTEST_DATAへ保存します。
この試験はJLineの実端末上の編集中入力を検証する試験ではありません。対話端末の入力保護は既存JLineの回帰テストに加え、Phase 4で実端末の検証を行います。

### 検証記録（PR #56）

- SpringなしのRootCommand読取りでのコンストラクタ不足を既存テストで確認し、修正後のヘルプ/補完/JLineを含む56件が成功しました。
- PR #55統合前の修正版全回帰は4,137件、failures 0、errors 0、skip 1（既存PlantUML）、10分26秒で成功しました。
- 初回Windows CIはmainのPR #55を含む組合せで4,179件、failures 0、errors 0、skip 1、9分47秒で成功しました（code head ed372ad9）。
- PR #55をこのブランチへ統合し、JNIへ渡す前に512サンプル・有限値・正規化範囲を確認する検査をRed/Greenで追加しました。関連38件（実JNI/WAVの3サイクルを行う1件を含む）が成功しました。
- 最終コードの全回帰・CIは [PR #56のチェック](https://github.com/mikoto2000/rei/pull/56/checks) を参照してください。
- 最終コード ae6c8d07 のローカル全回帰は4,182件、failures 0、errors 0、skip 1、11分26秒。Windows CI（run 37888375057）も4,182件、failures 0、errors 0、skip 1で成功しました。
- 2026-10-09 の実機試験で DRY (VT-4) → JavaSound → raw Silero VAD → base INT8 → 共通入力ゲートウェイ → 既存Agent → COMPLETED会話履歴まで、Enterなしで確認しました。20秒後の正常OFFも確認しました。診断ラッパーは本番のcapture/backend/coordinator/submitを使い、フレーム数・振幅・VAD確率・認識回数だけを収集します。録音は保存しません。
- 診断値: 618フレーム、316,416サンプル、nonzero 178,259、peak 0.759247、RMS 0.082684、VAD最大0.999568、閾値以上138フレーム、ASR 1回。
- 認識は「こんにちは、本性入力のテストです。 くしてください。」で、例文との誤認識・欠落があります。Agentは挨拶し、欠けた依頼の確認を返しました。自然発話の精度目標達成やCER測定の成功とは扱いません。モデル比較・精度評価はPhase 5に残ります。
- 手動試験プロセスは完了通知直後の履歴確認で終了コード1になりましたが、保存済み履歴を独立して読み取り、同じrunのCOMPLETED・認識文・非空応答を確認しました。ChatExecutionServiceは完了イベント後のfinallyで履歴を保存するため、補助コードを保存完了の最大15秒待機に修正しました。修正後の補助コードによる実機再試験はまだ実施していません。
- 直前の試験は受付表示がユーザーへ届かなかったため、有効な発話試験として数えません。モデル未配置からの自動取得、実端末の入力保護、長時間耐性は後続Phaseの未完了範囲です。
