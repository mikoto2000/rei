# Phase 5: Windows CPU の音声認識精度と遅延

本番モデルは PR #60 の Whisper large-v3-turbo FP32 を維持する。tiny multilingual、base multilingual INT8、small multilingual INT8 は比較測定専用で、本番の自動 fallback や量子化への切替には使わない。

UTF-8文字欠落を起こす固定ランタイムの問題を切り分け、byte保持対策を適用した。下記の先行比較値は修正前の履歴として保持し、修正後の測定結果とは区別する。現在の受入結果は末尾の修正後実マイク試験を参照する。

## 測定条件

2026-10-10、Windows、Java 25、Core i9-12900（16 cores / 24 logical processors）、RAM 137,167,679,488 bytes。sherpa-onnx 1.13.8 / CPU / 日本語 / greedy。推論は直列に実施した。OS と通常のデスクトップ処理を停止した専用実験環境ではない。

公開 FLEURS 日本語 validation の 3–18 秒の先頭20録音（238.5秒）と、Microsoft Haruka による合成診断10件を使用する。同じ文章 ID の別話者録音を行番号で区別する。初回の名前衝突を修正し、19件の既存結果と欠けていた1録音の追加測定を SHA-256 対応で再構成した比較結果も保持する。ユーザーのマイク録音は保存しない。

FLEURS: [固定 revision](https://huggingface.co/datasets/google/fleurs/tree/3dfbbb3b3cfb7d48550c24defda9876bbb73f8ac)、CC-BY-4.0。validation Parquet の SHA-256 は `4636065a2eb7d1bb07007d3193092eb1856cf101360831c61d6dfa611e579071`。データ作成者は Google。評価はこの小標本の結果であり、自然な自由発話への一般化を示すものではない。

CER は NFKC 後に句読点・空白を除き、Unicode code point の Levenshtein 編集数 / 正解文字数で算出する。大文字小文字や用語は同一視しない。無音の正解文字数は0なので CER に含めず、送信数を別計測する。p50/p95 は nearest-rank。

`VoiceBenchmark` の遅延は最後の VAD 陽性フレームから、無音確定待ち + 実 ASR + フィルタのサンプル時計による推定値である。実 Gateway、キュー待ち、ネットワーク、LLM 応答は含まない。`VoiceLatencyReplay` は実時間で公開音声を再生し、隔離 VAD/ASR、coordinator、実 Gateway と VOICE Run のローカル callback までを実測する。LLM/Tool は呼ばない。音響的な終了時刻の人手注釈ではなく VAD 陽性の終了時刻を基準とする。

CPU はプロセス CPU 秒 / 推論壁時計秒の core 相当値。設定4 threads は ONNX セッションごとの指定であり、プロセス全体の4 core上限ではない。メモリは所有子プロセスの WorkingSet を100ms周期で観測した最大値であり、瞬間最大や隔離構成全体の最大値ではない。

## 修正前の選定で棄却した設定

5公開録音と合成10件の予備比較では tail padding 500 frames が良好だった。しかし公開20録音に拡張すると、既定1000 frames / 1 thread の文字誤り106/917（11.56%）に対し、500 frames / 4 threads は153/917（16.68%）へ悪化した。速度だけで500 framesを採用しない。

VAD threshold .5、silence 1200ms では28発話中5件が複数候補になり、1800msでは2件に減った。公開20録音は1800msですべて単一候補になった。残る2件は合成の途中無音900/1500ms追加ケースで、SAPI自体の末尾・先頭無音も含む。これは自然発話での途中確定率ではない。無音・白色雑音2件の候補数は0。2400msはさらに1件減るが待ち時間が増えるため、1800msを候補とした。低音量の実人間発話は未評価のため threshold は .5 を維持する。

## 修正前の CPU 1 thread 比較

公開20録音の正解文字数917。ASR はクリップ全体の直接推論。

| モデル | CER | 平均RTF | ASR p50 / p95 (秒) | 観測ピークRSS (GiB) |
|---|---:|---:|---:|---:|
| base-int8 | 26.06% | 0.144 | 1.547 / 2.834 | 0.69 |
| small-int8 | 15.59% | 0.423 | 4.970 / 8.184 | 1.30 |
| tiny | 34.35% | 0.090 | 1.022 / 1.696 | 0.59 |
| turbo | 11.56% | 1.374 | 15.392 / 21.194 | 4.12 |

## 修正前に選定した CPU/VAD 設定

ASR は4 threads / tail padding 1000 frames、VAD は threshold .5 / silence 1800ms。モデルの固定 revision/SHA は変更しない。公開20録音の全文認識は1 thread / 1000 framesと全件一致し、CER 106/917（11.56%）を維持した。平均RTF .553、p95 RTF .630、ASR p50 6.238秒 / p95 8.807秒。最後のVAD陽性からフィルタまでの推定 p50 7.129秒 / p95 9.644秒。合成8発話では CER 49/315（15.56%）、推定p95 10.300秒。

RTF < 1 はこの公開20件で達成したが、投入p95 ≤ 3秒は未達。主なボトルネックは CPU の turbo FP32 全セグメント推論であり、1.8秒の無音確定待ちも加わる。モデルを軽量化するとこの標本のCERが悪化するため、ユーザー指定のFP32を維持して制約を明示する。GPU はPhase 6の検討対象で、この結果をGPU成功とは扱わない。

通常の操作例（停止中のみ変更可能）:

```text
/voice off
/voice config
/voice config --asr-threads 4 --asr-tail-frames 1000 --silence-ms 1800
/voice test
```

threads は1..4、paddingは250..2000 frames。VADのms単位のtailとは別設定。不正値はVADや確認設定も部分変更しない。通常起動はモデル取得・JNI・マイクを開始せず、設定確認だけで推論も開始しない。

## 評価の限界

発話欠落は28発話の候補数0を指し、語句の脱落はCERに反映する。無音/白色雑音の意図しない送信は2件中0であり、すべての環境で誤送信0という意味ではない。初期1200ms設定の公開録音先頭に「音楽」等の余分な候補が見られ、意図の人手注釈がないため一律に正当な発話と扱わない。1800ms候補は公開20件単一だが、合成の途中無音2件は依然分割される。

専門用語とファイル名は9語の厳密な文字列一致で turbo 4/9。意味が近い読みやカタカナ表記は一致として数えない。自然な考えながらの発話、実環境の雑音、多様な話者、物理USB抜去・睡眠復帰はこのベンチマークで検証していない。合成の無音挿入を自然発話の代用として成功扱いしない。

## 再現用の入口

- `poc/voice/extract_benchmark_fixtures.py`: 固定Parquet SHA確認、公開20録音をPCM16へ変換。`test_extract_benchmark_fixtures.py` は同じ文章IDの録音衝突を検出する回帰テスト。
- `prepare_synthetic_benchmark.ps1`: Microsoft Haruka の明示選択とPCM16出力。取得やマイク入力はしない。
- `prepare_benchmark_suite.py`: `--public` / `--synthetic` / `--output` で20公開+10合成の固定比較セットを構成。
- `VoiceBenchmark.java` / `benchmark.ps1`: Java 25、固定sherpa JVM/native JARで直接ASRとVAD+フィルタ推定を測定。所有子プロセスのみを制限時間で停止する。
- `VoiceVadBenchmark.java`: threshold/silenceの候補数を実Sileroで比較。ASR成功数ではない。
- `VoiceLatencyReplay.java`: 公開音声を実時間で入力し、実GatewayとVOICE Run、隔離worker終了を検証。

Python準備はpyarrow 26.0.0 / soundfile 0.14.0 / numpy 2.5.3を使用。モデル/JAR/WAVはリポジトリへ同梱しない。SHAと固定出典の取得確認後に測定する。[測定結果と録音メタデータ](voice-input-phase5-data/README.md)を保持する。

## 修正前の実 Gateway 診断

公開録音4件を実時間で入力し、実際の VAD/ASR 隔離worker → coordinator → Gateway → VOICE Run ローカルcallbackの経路を通した。すべて録音末尾まで再生後に投入され、VOICE provenanceを保持した。最後の VAD 陽性から投入まで5.219 / 6.675 / 6.587 / 9.167秒、nearest-rank p50 6.587秒 / p95 9.167秒。4件の小標本であり、前掲20件のオフライン推定とは同じ母集団でない。LLM応答時間は含まない。

各回 `/voice off` 相当の停止が OFF となり、所有隔離workerは0、認識・VAD失敗とキュー溢れは0だった。これは公開録音を用いた診断であり、DRY (VT-4) の更新後試験ではない。

## 比較モデルの固定出典

比較用のモデルは [sherpa の Whisper 配布案内](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/whisper/export-onnx.html) が指す csukuangfj の変換済み一式を用い、独自変換はしない。Whisper上流MIT、sherpa Apache-2.0、ONNX Runtime MIT。比較用モデルにも独立した配布LICENSEがない点はFP32移行文書と同じ扱いとする。

| 比較 | Hugging Face 固定 revision | encoder SHA-256 | decoder SHA-256 |
|---|---|---|---|
| tiny FP32 | `65176e2deb88badc814a94058666cadccc29b61c` | `42c1d4cbf889632ba21ab6f0d4064c80209755f265ce5cd630db4a6793e7089c` | `e144c07dc6b55cece24392811f2d934b97013811f5e677d1315d341a0a74a25d` |
| base INT8 | `bb53ee204431c90d314c1cc08d28d23e5b7927cc` | `0b8fb1304b6109976038efff5ace81720e00386f3ff6b54ee8c75291ca0a1e11` | `9759d217388a01b3a4c7c15533201067b48ae819c4daafc8624e64b9409dc02d` |
| small INT8 | `8f3c18b358db4d1f2fc1eae49d75cd20989e4309` | `4cbe7b22fa9026b843b60a68640c747de05bafb1a11b57edc0e66c232d9f33a9` | `acad50b5c782696e91b55914cc5ab4f756f1532f76e22aa6fc615f39fb69a8ee` |

各モデルのtokensは SHA `b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126`。比較でも固定1.13.8のjvm/nativeとSileroを利用し、実ロード・推論・解放で互換性を確認した。本番turboのgraph/sidecar固定値は [FP32移行](voice-input-turbo-fp32.md) に記載する。

追加のコマンド診断は1つのHaruka合成録音「git status、mvn test、git diff を実行してください。」を比較した。正解32文字のCERは tiny 75.00%、base INT8 31.25%、small INT8 68.75%、turbo FP32（確定設定）34.38%。3コマンドの厳密な文字列一致はそれぞれ0/3、1/3、0/3、0/3。turboの `GitStatus` / `MVNTest` / `GitDev` 等を正しいコマンドと扱わない。これは1録音の診断でありモデル全体の順位を決める根拠にはしない。

### 手順例

モデル配置は比較用 `target/voice-phase5-models/`（tiny/base/smallとsilero）、本番用 `target/voice-phase5-turbo/`（jvm.jar/native.jar/models以下の固定7ファイル）を使用した。取得のサイズ/SHAを確認してから、以下を実行する。Python依存は隔離したローカルディレクトリへ配置し、環境のPythonを変更しない。

```powershell
# 各コマンドはリポジトリルートで実行。python は必要な依存を導入済みの実行ファイル。
python poc/voice/extract_benchmark_fixtures.py target/voice-phase5-models/fleurs-ja-validation.parquet target/voice-phase5-unique-fixtures
./poc/voice/prepare_synthetic_benchmark.ps1 -Output target/voice-phase5-fixtures
python poc/voice/prepare_benchmark_suite.py
./mvnw.cmd -B -DskipTests package
New-Item -ItemType Directory -Force target/voice-benchmark-classes
javac -encoding UTF-8 -cp 'target/classes;target/voice-phase5-turbo/jvm.jar;target/voice-phase5-turbo/native.jar' -d target/voice-benchmark-classes poc/voice/VoiceBenchmark.java
./poc/voice/benchmark.ps1 -Profile turbo -Models target/voice-phase5-turbo/models -Fixtures target/voice-phase5-final-suite -Threads 4 -Tail 1000 -Silence 1800 -Label utf8-tuned
# tiny / base-int8 / small-int8 は比較Models、1 thread / 1000 / 1200、Label utf8-mainで比較する。
python poc/voice/summarize_benchmark.py
```

合成音声の再生成はWindowsの音声バージョンに依存する。今回保存したSHAと異なる場合は同一音声の測定と扱わず、新しいfixture metadataと結果を保存する。この環境では既存合成音声から構成した30件はすべて元のSHAと一致して再現した。再構成はモデルを実行しない。

## 修正前の自動検証

2026-10-10、`.github/ci/full-test.ps1`（1200秒の所有プロセス制限）による全体回帰は **4,286件、failures 0、errors 0、skipped 1** で成功した（11分46秒）。スキップは実PlantUML実行の条件が成立していない `DocumentRendererProcessTest.realPlantUmlErrorAndPngAreVerifiedAndDeliveredThroughExistingStore` で、音声の成功として数えない。関連39件、録音衝突防止Pythonテスト1件、公開30音声のSHA再現、実Gateway4件のworker終了、`git diff --check`、Spring Boot JARのpackageも成功。

新APIは未実装状態のRed確認後に実装してGreenを確認した。公開録音の文章ID衝突も、同一ファイル名で上書きする状態のRedを確認し、行番号を含む名前へ修正してGreenを確認した。500 framesの予備選定は全件評価で棄却し、1000 framesへ戻した後の関連テストと全体回帰を実施した。

## DRY (VT-4) の診断付き再試験

2026-10-10 05:24 JST、Java 25 / turbo FP32 / 4 threads / padding1000 / silence1800ms を実ログで確認し、ユーザーの新しい準備返答と「受付開始」の表示後に20秒の実マイク試験を行った。古い待機アプリは準備期限切れで終了していたため、新しい試験アプリ・readiness markerを用いた。通常利用中のアプリは停止していない。

620 frames / 317,440 samples（19.84秒）、nonzero 148,806、peak .315094、RMS .025699、VAD peak .999283、speech frames 130、recognitions 1。音声はファイル保存していない。固定FP32モデルから共通Gatewayへ自動投入し、実Agent Run `501d017c-cfa2-4ee6-8ca1-f3b9d428e787` の保存状態は `COMPLETED`。音声状態OFF、所有推論worker0、試験アプリも終了、stderrは空だった。

指定文は「こんにちは。音声入力のテストです。短く挨拶してください。」。実際の認識文は「こんにちは。音声入力のテストです。くしてください。」で、挨拶の指示部分が欠落した。Agentは欠けた指示の確認を含む長い応答を返した。したがって入力経路・終了処理は成功したが、全文認識と短い挨拶の受入は未達である。helperのPASS表示は経路の検証であり、認識精度や返答スタイルの成功を意味しない。録音を保持していないため、音響入力・VAD区間・ASRのどこで語句が欠けたかは断定しない。この1回を一般的な認識精度の証明とは扱わない。

## 認識欠落の追加切り分け

診断helperに明示オプション `--whole-clip-diagnostic` を追加した。既定では無効。本番コードやモデルは変更しない。有効時だけ、最大28秒分のPCMを試験プロセスのRAMに保持し、160ms単位の音量/VAD集計、各ASR区間の長さ・認識時間・フィルタ前の文を表示する。マイクをOFFにした後、実際に取得した20秒全体を同じFP32設定の所有隔離workerで再認識し、区間切出しの認識結果と比較する。追加結果はAgentへ送信しない。

音声ファイルは書き出さず、全体比較のfinallyでPCMバッファをゼロクリアする。比較workerもcloseし、残留0を確認する。区間だけと全体で結果が異なれば区間・文脈の影響を調べられるが、その差だけで特定の音響原因を断定しない。同じ結果でも入力音声とASRのどちらが原因かは確定しない。追加再試験の実測結果は次節に記録する。

追加比較の初回（05:34頃）は受付監視が共有中のログを排他的に読もうとして失敗し、受付表示が約7秒遅れた。音声のVAD陽性は取得開始14.72–19.84秒付近にあり、無音確定前に20秒の停止へ到達した。recognitions 0 / 自動投入なしだったため、区間と全体の比較は成立しておらず有効な受入成功と扱わない。全体のみの再認識は同じ語句欠落を示したが、受付時間のずれを踏まえて原因は断定しない。

比較試験の手順を修正した。追加比較オプション時はエンジン起動完了を `ARMED` で確認し、新しいcapture cueを作るまで実フレームを診断・ASRへ渡さない。cue直後に20秒の受付を開始して通知する。ログ監視は共有読み取りを使用する。既存5引数の通常helper手順は維持する。修正後の受付同期は次節の実試験で確認した。


### 受付同期修正後の比較と文字欠落の原因

20秒の第4試験では、625 frames / 320,000 samples、nonzero 133,643、peak .208923、RMS .013771、VAD peak .999020、speech frames 126、recognitions 1。5.364秒の切出し区間（ASR 3963.586ms）とRAM内20秒全体が、どちらも「こんにちは。音声入力のテストです。くしてください。」となった。Run `e5c72e40-b026-4fc8-a593-e85429f551ef` はCOMPLETED、OFF / 所有worker0 / 試験アプリ終了、stderr空。受付同期と経路は成立したが、文字欠落と短い返答の受入は未達。

同じ欠落はマイクを使わないHaruka合成文「こんにちは。短く挨拶してください。」でも再現した。語全体ではなく「短」「挨」「拶」の文字が落ちて「こんにちは。くしてください。」となる。ネイティブ結果のtoken列に空文字が現れた。固定sherpa 1.13.8ではbyte-level BPEの各断片を結合前にUTF-8として清掃するため、分割された文字バイトが消える。[上流修正 2edc882](https://github.com/k2-fsa/sherpa-onnx/commit/2edc882fe0ba07882daf2cde8bc3bfa5dd968d3b) は結合後に清掃するよう変更している。従来のCERはこのランタイム不具合込みの値であり、モデル本来の精度とみなさない。

正式固定JARを維持する回避策を検証中。元tokensのSHA検証後、各byteをU+E000–U+E0FFで表す一時語彙を生成し、認識結果全体の結合後に元byteへ戻してUTF-8を厳密に復号する。token ID / モデル重み / FP32 / 言語 / 推論設定は不変。想定外文字や不正UTF-8は拒否し、語句の推測補完を行わない。認識器の解放時と初期化失敗時に所有一時ファイルを削除する。将来ランタイムを更新する場合は、この変換と復号をセットで再検証する。

合成短文の対策PoCでは「こんにちは。短く挨拶してください。」の全文を復元した。製品経路の実マイク試験、修正後の全体回帰、性能・精度の再測定はまだ未完了である。


### byte保持対策後のDRY (VT-4)試験

2026-10-10 08:13 JST、新しい準備OK後に固定FP32モデルで第6試験を行った。先に準備した第5試験はマイクOFFの待機期限で終了しており、新しい所有アプリに置き換えた。ARMEDからcapture cueを送り「受付開始」を表示、625 frames / 320,000 samples（20秒）、nonzero 161,252、peak .282867、RMS .018229、VAD peak .998587、speech frames 128、recognitions 1。

5.492秒の切出し区間を3979.975msで認識し、「こんにちは。音声入力のテストです。短く挨拶してください。」と全文一致した。マイクOFF後のRAM内20秒全体も同じ全文だった。追加の全体認識はAgentへ送信していない。Agent Run `67022ca1-846a-4990-9675-48d34bcf1b89` は保存状態COMPLETEDで、返答は「こんにちは。音声入力、しっかり届いていますよ。今日もよろしくお願いします。何かお手伝いできることがあれば、お気軽にどうぞ。」だった。通常モードの返答であり、Phase7会話モードの受入とは扱わない。

入力経路、今回の全文認識、OFF時の所有worker0を確認した。音声ファイル保存なし。1回の手動試験であり、自然発話全体の精度やp95を証明するものではない。修正後の関連テスト8件（新規byte保持テスト3件を含む）は成功、合成短文を製品隔離factoryで実行した結果も全文一致・所有worker0だった。修正後の性能再測定と全体回帰は引き続き実施する。


## byte保持対策後のFP32再測定

同じ公開20録音・正解917文字では、誤り41文字 / CER **4.47%**（対策前106文字 / 11.56%）。4 threads / padding1000 / silence1800の新規30件実行で、公開20件の平均RTF **0.609**、p95 RTF .672、直接ASR p50 6.655秒 / p95 10.013秒。最後のVAD陽性からフィルタまでの推定p50 7.467秒 / p95 **10.796秒**。投入p95 3秒の目安は未達であり、CPU全区間ASRと1800msの無音確定待ちが引き続きボトルネックである。LLM応答時間を含めていない。

同じ実行の合成8発話では誤り34/315文字 / CER10.79%、取りこぼし0、複数候補2件。無音・白色雑音の2件は投入0。専門用語・ファイル名の厳密一致は4/9で、変換や補完を成功として数えない。30件の専用所有プロセス最大WorkingSetは4,459,020,288 bytes（100ms観測）、CPU1590.609秒、終了コード0。修正前・修正後は同じ音声と設定だが専用の無負荷環境ではなく、速度差をbyte処理だけの因果と断定しない。

比較モデルとpadding候補の修正後再測定は完了した。全体回帰の結果は次節に記録する。CI・main統合は別途確認する。


### 対策後の比較モデル（公開20録音）

各モデルで同じ30件セットを新規実行し、以下は公開20録音だけを集計した。性能値は表の推論設定ごとの測定であり、全モデルが同じスレッド数・silenceという比較ではない。精度は同じ917正解文字で評価する。本番はユーザー指定のFP32を維持する。

| モデル | threads / tail / silence ms | CER | 平均RTF | 直接ASR p95秒 | フィルタ投入推定 p95秒 | 最大WorkingSet GiB |
|---|---|---:|---:|---:|---:|---:|
| tiny | 1 / 1000 / 1200 | 33.81% | 0.080 | 1.554 | 2.547 | 0.630 |
| base-int8 | 1 / 1000 / 1200 | 24.21% | 0.135 | 3.189 | 3.527 | 0.731 |
| small-int8 | 1 / 1000 / 1200 | 10.91% | 0.385 | 7.345 | 7.900 | 1.339 |
| turbo | 4 / 1000 / 1800 | 4.47% | 0.609 | 10.013 | 10.796 | 4.153 |

全モデルの28発話で取りこぼし0、無音・白色雑音2件の投入0。silence1200msの比較モデル3つでは公開2/20、合成3/8が複数候補になり、silence1800msの本番FP32では公開0/20、合成2/8だった。この違いはVAD設定の違いも含む。自然な思考中の無音に対する一般的な率とは扱わない。

対策後のコマンド1録音のCERは tiny75.00%、base INT8 31.25%、small INT8 68.75%、turbo FP32 34.38%。厳密なコマンド一致は0/3、1/3、0/3、0/3で、byte欠落修正だけでは英語の綴り誤りや空白不足は解消しない。音声による非read-only Toolには引き続き引数に結び付いた明示承認が必要。


### 対策後のpadding候補

byte保持対策後にも4 threads / padding500 / silence1800で公開20録音を新規実行した。誤り98/917文字 / CER **10.69%**、平均RTF 0.495、直接ASR p95 8.064秒、フィルタ投入推定p95 8.941秒。padding1000の41/917文字 / 4.47%より悪化し、速度だけで500を選ばない。修正後にも**padding1000を維持**する根拠を得た。VAD候補数や無音の確定を改善する設定と、モデルの文字精度の改善を混同しない。

PR #61のUser枠表示をmainからPhase5へ取り込んだうえで、修正後の全体回帰を開始した。実マイク第6試験はこの表示変更を取り込む前の経路で行った記録であり、後から実機の表示検証済みに読み替えない。


## 修正後の全体回帰

PR #61統合後の `.github/ci/full-test.ps1 -TimeoutSeconds 1200` は **4,301件、failures 0、errors 0、skipped 1** / 12分26秒で成功した。スキップは既存の実PlantUML条件未成立試験で、成功件数として扱わない。新規byte保持3件を含む関連8件のRed/Green、製品隔離ASRの合成短文、実マイク第6試験、固定音声の修正後再測定も完了した。最新mainに対する診断helperのコンパイル、`mvnw -B -DskipTests package`、`git diff --check` は成功。


### 修正後の実Gateway再生

最新mainのPR #61とbyte保持対策を含むコードで、公開録音4件を実時間で再生した。VAD/ASR隔離worker → coordinator → 実Gateway → VOICE Runローカルcallbackの経路で4件とも録音末尾の再生後に投入され、Run数は1/2/3/4。LLM・Tool・実マイクは使わない。各回OFF・所有worker0、fault/queue overflowなしを確認した。

最後のVAD陽性からGatewayまで5.765 / 7.674 / 7.261 / 9.965秒、nearest-rank p50 **7.261秒** / p95 **9.965秒**。公開4件の小標本であり、20件のオフライン推定p95 10.796秒と同じ母集団ではない。これも投入p95≤3秒を満たしていない。生の結果は `voice-input-phase5-data/utf8-gateway.tsv` に保持する。

欠落の再現用 `VoiceTokenDiagnostic.java` はマイク・Agentなしの合成/公開音声専用。同じ固定JAR・モデルで、`--byte-safe` なしなら旧token清掃、付けると製品のbyte保持クラスを使う。モデルディレクトリ / turbo / WAVを引数にし、実機確認に使う場合でもユーザーの音声ファイルを暗黙作成しない。
