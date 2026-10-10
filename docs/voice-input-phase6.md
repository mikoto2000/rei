# 高度な音声機能（Phase 6）

Java 25 / Windows x64 / DRY (VT-4) / Whisper large-v3-turbo FP32を維持する。
Phase 5のモデルID・配布元・固定SHA・CPU推論は変更しない。

## 対応範囲

| 項目 | 実装・確認した範囲 | 制約・未対応 |
| --- | --- | --- |
| GPU | RTX 4070 Ti SUPER（16,376 MiB、driver 616.64）で固定JNIにCUDA指定を与える診断を実施。CPUExecutionProviderのみでCPUへフォールバックするログを確認 | 現在の固定配布物でGPU推論は未対応。GPU対応JNI、CUDA依存物、配布・固定SHA・回帰検証が別途必要。CPUで正しく認識した結果をGPU成功と扱わない |
| ウェイクワード | 任意の認識後プレフィックス受付。既定語「れい」は「レイ」も受理。語境界を要求し、本文の全角文字・ファイル名は保持する。単独呼びかけは同じSessionの次の発話1件を60秒だけ受理 | 常時低消費電力の専用KWSではなく、呼びかけ条件外でもASR処理は必要。日本語用の検証済み専用KWSモデルは追加しない。呼びかけは本人確認やツール承認の代替にならない |
| 処理中の割り込み | 有効時に「実行を停止」（末尾句点可）という明示発話を、同じShellクライアント・Project/root・Sessionが開始した実行中Runの取消へ接続。入力IDの再送を重複排除する | 発話末尾検出とCPU ASRを待つため即時停止ではない。他クライアント・Web・待機Run・Project全体を停止しない。停止フレーズ以外の新しい依頼は従来のFIFOへ送る。再開には明示依頼が必要 |
| 発話者識別 | 単独利用の要件では必須ではないと評価 | 未実装。追加の検証済みモデル、登録音声の扱い、本人確認との境界・誤判定評価が必要。SDKにAPIがあることだけで対応済みとは扱わない |
| TTS | 任意のWindows内蔵SAPI音声。明示選択したインストール済み音声を使用。現在LISTENINGかつ同じ所有Shell・SessionのVOICE Runの正常完了応答だけを読み上げる。既定はMicrosoft Haruka Desktop - Japanese | Windows既定の出力先を使用。音声が未インストールなら別の音声へ自動変更せず失敗を表示。外部音声サービス・音声ファイル保存は追加しない |
| 自己音声再認識防止 | 共通の半二重ゲート。読み上げ中と余韻中はVAD前にPCMを抑止し、区間・待機キューを破棄する。世代番号で読み上げ開始前から認識途中だった結果も送信しない。既存の設定済み音声通知にも適用する | 物理的なAECではない。読み上げ中の割り込み発話は受け付けない。DRY (VT-4)の20秒診断で読み上げ完了・認識0件を確認。他のスピーカー・マイク経路の評価は未実施 |

## 明示設定

高度機能は既定OFF。変更は `/voice off` 後、OFFになってから行う。

```text
/voice features
/voice features --wake true --wake-word れい
/voice features --interrupt true
/voice features --tts true --tts-voice "Microsoft Haruka Desktop - Japanese"
/voice features --echo-tail-ms 800
/voice on
```

全機能を無効にする例:

```text
/voice off
/voice features --wake false --interrupt false --tts false
```

呼びかけ有効時は「れい、こんにちは」などと話す。停止例は「れい、実行を停止」。
認識確認が有効なら停止も確認後に実行する。音声による通常のツール操作は従来どおりVOICEの引数付き明示承認を必要とする。

余韻は250〜3000 ms、既定800 ms。読み上げは同時1件・待機2件、1応答16,384文字まで、プロセス開始後3分以内。
上限超過では画面の完全な応答を保持し、読み上げの破棄を固定メッセージで表示する。
文章は標準入力JSONで渡し、固定PowerShellスクリプトでSAPIの `SVSFIsNotXML`（16）を指定する。
文章をコマンド引数・スクリプト・SSMLとして解釈しない。OFF・送信先変更・終了時は自分の読み上げを取り消す。

## 検証記録

- 各追加機能の先行失敗テストから実装し、関連58件成功。
- 追加の期限・キュー検証9件成功。標準入力書込み停止も時間制限対象とする関連31件成功。旧通知への迂回防止7件、停止・通常入力間のID共有28件も成功。全体回帰4,322件（失敗0・エラー0・スキップ1）と配布JARのpackage成功。スキップは既存の実PlantUML条件付き試験。
- 実SpringアプリをWeb無効の別プロセスで起動し、初期マイクOFF・高度機能OFF・ネイティブワーカー0と正常終了を確認。通常アプリの18080ポートは使用していない。
- 実SAPIで「読み上げのテストです」を1回再生し正常終了。利用者から「聞こえました」の回答を得た。マイクは開かず、音声ファイルは作成していない。
- GPU診断は `Available providers: CPUExecutionProvider` と `Fallback to cpu!` を出力した。GPU実行の成功例ではない。
- 配布JARとDRY (VT-4)で20秒の無発話診断を実施。読み上げ完了・認識0件・障害0件・終了時OFF・ネイティブワーカー0、アプリ終了・標準エラー空を確認。Agent送信・録音ファイル保存なし。利用者から今回の読み上げも「聞こえました」の回答を得た。最初の試行は受付合図の期限切れで中止し、成功件数に含めていない。合図待ちをマイクOFF・診断期限開始前へ移して再試験した。CI・main統合はこの記録時点で未確認。Phase 7にはmain統合後に進む。
- Phase 5で報告したCPU遅延の制約（p95 3秒目標未達）は残る。

## 根拠

[Java/JNI API](https://k2-fsa.github.io/sherpa/onnx/java-api/non-android-java.html)、
[専用KWSの公開モデル](https://k2-fsa.github.io/sherpa/onnx/kws/index.html)、
[SAPI Speak flags](https://learn.microsoft.com/en-us/previous-versions/windows/desktop/ms720892(v=vs.85))を参照。
GPU判定には固定v1.13.8の実配布物と同版 `session.cc` の診断を使用した。
