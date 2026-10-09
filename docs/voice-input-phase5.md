# Phase 5: Windows CPU の音声認識精度と遅延

本番モデルは PR #60 の Whisper large-v3-turbo FP32 を維持する。tiny multilingual、base multilingual INT8、small multilingual INT8 は比較測定専用で、本番の自動 fallback や量子化への切替には使わない。

## 測定条件

2026-10-10、Windows、Java 25、Core i9-12900（16 cores / 24 logical processors）、RAM 137,167,679,488 bytes。sherpa-onnx 1.13.8 / CPU / 日本語 / greedy。推論は直列に実施した。OS と通常のデスクトップ処理を停止した専用実験環境ではない。

公開 FLEURS 日本語 validation の 3–18 秒の先頭20録音（238.5秒）と、Microsoft Haruka による合成診断10件を使用する。同じ文章 ID の別話者録音を行番号で区別する。初回の名前衝突を修正し、19件の既存結果と欠けていた1録音の追加測定を SHA-256 対応で再構成した比較結果も保持する。ユーザーのマイク録音は保存しない。

FLEURS: [固定 revision](https://huggingface.co/datasets/google/fleurs/tree/3dfbbb3b3cfb7d48550c24defda9876bbb73f8ac)、CC-BY-4.0。validation Parquet の SHA-256 は `4636065a2eb7d1bb07007d3193092eb1856cf101360831c61d6dfa611e579071`。データ作成者は Google。評価はこの小標本の結果であり、自然な自由発話への一般化を示すものではない。

CER は NFKC 後に句読点・空白を除き、Unicode code point の Levenshtein 編集数 / 正解文字数で算出する。大文字小文字や用語は同一視しない。無音の正解文字数は0なので CER に含めず、送信数を別計測する。p50/p95 は nearest-rank。

`VoiceBenchmark` の遅延は最後の VAD 陽性フレームから、無音確定待ち + 実 ASR + フィルタのサンプル時計による推定値である。実 Gateway、キュー待ち、ネットワーク、LLM 応答は含まない。`VoiceLatencyReplay` は実時間で公開音声を再生し、隔離 VAD/ASR、coordinator、実 Gateway と VOICE Run のローカル callback までを実測する。LLM/Tool は呼ばない。音響的な終了時刻の人手注釈ではなく VAD 陽性の終了時刻を基準とする。

CPU はプロセス CPU 秒 / 推論壁時計秒の core 相当値。設定4 threads は ONNX セッションごとの指定であり、プロセス全体の4 core上限ではない。メモリは所有子プロセスの WorkingSet を100ms周期で観測した最大値であり、瞬間最大や隔離構成全体の最大値ではない。

## 選定の途中で棄却した設定

5公開録音と合成10件の予備比較では tail padding 500 frames が良好だった。しかし公開20録音に拡張すると、既定1000 frames / 1 thread の文字誤り106/917（11.56%）に対し、500 frames / 4 threads は153/917（16.68%）へ悪化した。速度だけで500 framesを採用しない。

VAD threshold .5、silence 1200ms では28発話中5件が複数候補になり、1800msでは2件に減った。公開20録音は1800msですべて単一候補になった。残る2件は合成の途中無音900/1500ms追加ケースで、SAPI自体の末尾・先頭無音も含む。これは自然発話での途中確定率ではない。無音・白色雑音2件の候補数は0。2400msはさらに1件減るが待ち時間が増えるため、1800msを候補とした。低音量の実人間発話は未評価のため threshold は .5 を維持する。

## CPU 1 thread の比較

公開20録音の正解文字数917。ASR はクリップ全体の直接推論。

| モデル | CER | 平均RTF | ASR p50 / p95 (秒) | 観測ピークRSS (GiB) |
|---|---:|---:|---:|---:|
| base-int8 | 26.06% | 0.144 | 1.547 / 2.834 | 0.69 |
| small-int8 | 15.59% | 0.423 | 4.970 / 8.184 | 1.30 |
| tiny | 34.35% | 0.090 | 1.022 / 1.696 | 0.59 |
| turbo | 11.56% | 1.374 | 15.392 / 21.194 | 4.12 |

## 確定した CPU/VAD 設定

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

## 実 Gateway の診断

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
./poc/voice/benchmark.ps1 -Profile turbo -Models target/voice-phase5-turbo/models -Fixtures target/voice-phase5-final-suite -Threads 4 -Tail 1000 -Silence 1800 -Label tuned
# tiny / base-int8 / small-int8 は比較モデルのModelsを指定し、1 thread / 1000 / 1200で比較する。
python poc/voice/summarize_benchmark.py
```

合成音声の再生成はWindowsの音声バージョンに依存する。今回保存したSHAと異なる場合は同一音声の測定と扱わず、新しいfixture metadataと結果を保存する。この環境では既存合成音声から構成した30件はすべて元のSHAと一致して再現した。再構成はモデルを実行しない。

## 自動検証

2026-10-10、`.github/ci/full-test.ps1`（1200秒の所有プロセス制限）による全体回帰は **4,286件、failures 0、errors 0、skipped 1** で成功した（11分46秒）。スキップは実PlantUML実行の条件が成立していない `DocumentRendererProcessTest.realPlantUmlErrorAndPngAreVerifiedAndDeliveredThroughExistingStore` で、音声の成功として数えない。関連39件、録音衝突防止Pythonテスト1件、公開30音声のSHA再現、実Gateway4件のworker終了、`git diff --check`、Spring Boot JARのpackageも成功。

新APIは未実装状態のRed確認後に実装してGreenを確認した。公開録音の文章ID衝突も、同一ファイル名で上書きする状態のRedを確認し、行番号を含む名前へ修正してGreenを確認した。500 framesの予備選定は全件評価で棄却し、1000 framesへ戻した後の関連テストと全体回帰を実施した。
