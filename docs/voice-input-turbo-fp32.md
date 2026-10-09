# 音声認識を Whisper large-v3-turbo FP32 へ移行

## 変更と対象

Whisper base multilingual INT8 を、sherpa-onnx 公式変換済みの **large-v3-turbo（配布名 turbo）FP32** に切り替えます。独自変換・再量子化は行いません。FP32 は FP16 より高精度な演算形式ですが、それだけで認識精度や速度が改善するとは限りません。

sherpa-onnx 1.13.8、Windows x64 CPU 1 thread、Silero VAD、VAD/ASR 別子 JVM、選択マイク、確認・訂正、共通入力 Gateway、VOICE の Tool 承認、停止と遅延結果破棄は維持します。GPU や wake word、会話スタイルは変更しません。通常起動でモデルの取得・JNI ロード・マイク起動はしません。

## 取得と旧キャッシュ

```text
/voice off
/voice models info
/voice models install --approve sherpa-1_13_8-whisper-turbo-fp32-2ca6ff69-silero-9e2449e1
/voice models status
/voice models verify
/voice devices
/voice device set <一覧の ID>
/voice test
```

`info` で配布元・ライセンス・全ファイルのサイズと SHA-256 を確認してから承認します。取得完了後もマイクは自動開始しません。`test` は20秒の診断で、Agentへ送信しません。会話に使う場合は選択先を確認し、`/voice on` を実行してください。

新しい7ファイルの合計は **3,247,195,692 bytes（約3.02 GiB）**。初回は残り取得量に64 MiBを加えた空き容量を確認し、以後も各ファイル前に再確認します。これはディスク予約ではなく、他プロセスによる同時消費や推論 RAM の確保を保証しません。旧版・退避版に必要な容量は別です。

新しい一式は `<rei-data-dir>/voice/managed/sherpa-1_13_8-whisper-turbo-fp32-2ca6ff69-silero-9e2449e1/` に保存します。旧 base の managed ディレクトリと手動配置は上書き・削除しません。旧 manifest ID の承認では新モデルを取得できません。新版は旧 base を自動 fallback として利用しません。戻す場合は対応する旧アプリ版と旧一式を組み合わせてください。

staging の全ファイルをサイズ・SHA-256で検証してから、同じファイルシステム内で一式を atomic move します。途中のモデル・旧版の一部と新モデルを組み合わせません。破損や sidecar 欠落は READY にならず、再利用時にも検証します。

## 配布元・sidecar・ライセンス

固定モデル revision: `2ca6ff69fc878651b770880507669577ac41c2ff`

[公式 sherpa の案内](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/whisper/export-onnx.html)が指す[固定配布一式](https://huggingface.co/csukuangfj/sherpa-onnx-whisper-turbo/tree/2ca6ff69fc878651b770880507669577ac41c2ff)を利用します。

| ファイル | bytes | SHA-256 |
| --- | ---: | --- |
| turbo-encoder.onnx | 735920 | 1b960f278564fb8bbacd544d4f85f4dd6d8a64d3aa89543d8f2c4021c926f976 |
| turbo-encoder.weights | 2600325120 | 746f879ecf066450ab0cdecc05383380b85157270ff6c0a9fb7cfdd917036e12 |
| turbo-decoder.onnx | 636209532 | b24db5d90fa230c5eaa6b823d74862ced9e0d1dc3e01ec46601968e8db0e09ec |
| turbo-tokens.txt | 816730 | b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126 |

encoder の ONNX graph と外部 weights は同じ models ディレクトリへこの名前で置きます。decoder の重みは ONNX 内部です。追加の jvm.jar/native.jar/Silero は従来と同じ固定サイズ・SHA。Windows native JAR の SHA を実取得で確認し、内包 ONNX Runtime の FileVersion/ProductVersion は1.28.2でした。

ライセンスは [Whisper 上流 MIT](https://github.com/openai/whisper/blob/main/LICENSE)、[sherpa-onnx Apache-2.0](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/LICENSE)、[ONNX Runtime MIT](https://github.com/microsoft/onnxruntime/blob/v1.28.2/LICENSE)、[Silero MIT](https://github.com/snakers4/silero-vad/blob/master/LICENSE)。変換済み配布には独立した LICENSE の宣言がないため、Whisper 上流の出典を維持します。アプリへモデル/JARは同梱せず、利用者が承認後に配布元から取得します。

## 大型モデルの上限と取消

- 単一ファイルの上限3 GiB、一式の上限4 GiB。サイズ・進捗・合計は long で扱います。
- HTTPS、証明書検証、リダイレクト上限5回、正確な Content-Length/実読み取りサイズと SHA 検証を維持します。
- 接続15秒、HTTP要求の応答ヘッダー待ち10分、取得から検証・有効化まで全体1時間。ストリーム body をファイルごと10分に制限する実装ではありません。各ファイル最大3回の試行と250/500 ms待機を維持します。
- `/voice models cancel` は HTTP body を閉じ、取得・検証ワーカーを中断します。SHA 検証も64 KiBごとに割込みを確認します。取消・失敗は自分の staging のみを清掃します。
- VAD子 JVM の起動上限30秒、ASR子 JVM は3分。VADはJARとSileroのみ、ASRはJARとWhisper graph/weights/tokensを検証します。VADが3 GBのASRモデルを重複ハッシュしません。
- VAD要求は5秒、ASR要求は3分。音声最大28秒・応答64 KiB・既存キュー上限を維持します。3分は高速動作の保証ではなく異常時の停止上限です。
- `on`/`test` は初期化を最大4分待ち、LISTENING になるまで受付成功とは表示しません。両子 JVM の順次起動・一式検証・マイク初期化に余裕を設けています。タイムアウトや待機中断で停止します。
- 起動中の停止は所有 supervisor の初期化を中断し、所有 child を終了させます。割込み済みの終了処理でも `destroyForcibly()` の非同期完了を最大3秒待ち、割込み状態を復元します。他のJavaプロセスは終了しません。

## 検証と未検証範囲

2026-10-09、公式一式の全SHAと実推論をクラウド Linux x86_64 で確認しました。公開 `test_wavs/0.wav`（英語、16 kHz、6.625秒）を利用し、一般的な認識精度の評価やユーザーの自然発話とは扱いません。

- sherpa-onnx 1.13.8 native / CPU 1 thread: 読込3.010秒、認識15.307秒、最大RSS3.489 GiB。
- Java 25 / Rei の loader・reflection・role別検証を使う standalone snapshot: 読込（SHA検証込み）5.700秒、認識15.175秒、プロセス全体21.059秒、最大RSS3.526 GiB。認識・native解放・classloader closeは成功。
- 隔離パイプラインでも実VAD/ASRの2子JVMを2回起動・終了しました。各回208 VAD framesの確率が有効で、ASRは参照文に一致。起動5.962/5.794秒、認識14.965/15.562秒、各終了後 `liveWorkers=0`。親と両子の合計最大RSSは3.686 GiB、観測した全5プロセスは終了しました。IPC・worker・時間上限のコードは本番と同一です。
- Java検証用コピーだけを公式Linux native JARと英語設定に変更し、本番Windowsのmanifestと日本語設定は維持しました。モデル変換はしていません。
- どちらも参照文に大文字小文字を除いて一致しました。メモリ/RSS・時間の監視付きで実行し、上限到達はありません。
- Whisper の128 melは[固定実装がモデル metadataから取得](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/sherpa-onnx/csrc/offline-recognizer-whisper-impl.h#L64-L67)します。JavaのFeatureConfigを変更せず上記推論が成功しました。

この計測はWindowsの速度・メモリや全音声での応答時間を保証しません。Java heapとは別にnativeのモデルメモリが必要です。base INT8より保存量・RAM負荷は大きくなります。

Windows DLL/JNI・DRY (VT-4) の実マイク、日本語自然発話、実機の開始/停止/再開、最大発話長、実機の負荷は未検証です。モデル取得後はまず `/voice test` と、必要なら認識確認モードを使い、誤認識・負荷を確かめてください。

通常の回帰はモデルを取得せず、録音しません。実機用 `SherpaBackendIT`/`IsolatedVoiceBackendIT` は、承認済みの完全一式と日本語fixtureを別途指定する opt-in のままです。旧 `poc/voice/prepare.ps1` はPhase0のbase比較を再現するための履歴用で、新モデルの取得には使いません。

### 回帰の進め方

固定manifest/長整数サイズ/中断可能なSHA/起動取消のRed（29件中3 failures・1 error）を確認してから実装しました。さらに割込み済みcallerからの子終了で `isAlive=true` が残るケースをRedで再現し、終了を有限時間待つ変更後にGreenを確認しました。関連95件は成功しました。その後、ASRのhandshake中断と先に起動したVADの終了・再起動、割込み済みの再利用検証が新規取得を開始しないケースも追加しています。

クラウドのテストでは、Javaの自動agent attachが使えないため既存Mockito JARをテストJVMの `-javaagent` で指定しました。SQLiteの実テストは公式 `sqlite-vec 0.1.9` のarchiveを配布manifestのSHAで検証してテストcacheに準備しています。SSL検証を無効化せず、製品コードやグローバル設定は変えていません。

最終の `./mvnw -Pfull verify` 相当（上記クラウド用テストJVMオプション付き）は **4,278件、failures 0、errors 0、skipped 2** で成功し、Spring Boot JARも生成しました。スキップはLinux上のWindowsパス補完1件と、実PlantUML実行条件未成立1件です。音声関連97件は全件成功しています。受入試験Javaコード3本もJava25でコンパイルしました。`git diff --check` は成功しています。Windows CIと実機の未検証事項は上記の通りです。
