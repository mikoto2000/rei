# Phase 7 OpenAI 実接続受入記録

更新: 2026-11-25 10:18 JST。対象 main は `38a13f1d38f0759049290e37df40cdd078a58e54`。
PR #635 の実装・検証中であり、最新 head の全 CI・通常マージ・マージ後 CI は未完了。
本番 gate、利用者設定、実行中の Rei、本番 DB は変更していない。

## 接続と隔離

ユーザー指定の `https://api.openai.com` / `gpt-5.6-sol`、`OPENAI_API_KEY` を使用した。
通常 low、計算課題 medium。Spring AI 2.1.0 / OpenAI Java SDK 4.101.0 の既存 Adapter を通る。
`store:false`、手動履歴、SDK retry 0、既存 one-shot transport を維持した。
別 API への fallback はない。提供元の会話 ID は利用しない。

通常の provider gate と fixture の外部接続拒否を維持し、package-private な明示的
`forResponsesAcceptance` 構成だけが事前に確定した endpoint snapshot と transport policy を受け取る。
Spring Bean や外部設定からこの構成を選ぶ経路はない。未承認 feature/settings は拒否する。
fixture / 本番 / acceptance の境界を追加8テストと従来4テストで検査した。

子 JVM は環境を消去し、OS 起動に必要な2変数、明示したキー、所有する home/tmp/data のみを注入する。
本番 application.yaml、利用者履歴を読み込まない。Scheduler、外部業務連携、Web server を起動しない。
手動構成した実 Core 経路の受入であり、Spring Boot アプリ全体の起動受入は Phase 9 に残る。

実データの保存先は Git 管理外の
`F:/project/rei/target/responses-api-workflows/phase7-10-20261125/`。
元の native payload は隔離 SQLite だけに保存する。報告は数値・型・ハッシュ・件数のみ。
キーや推論全文をログ、PR、fixture、fingerprint に保存しない。

## 予算

初期承認枠は7/10要求、入力559・出力178（推論123）、HTTP200×7。
追加枠は別台帳で200要求・入力100万・出力30万・3時間・追加USD20。
開始は約09:27 JST、期限は約12:27 JST。再実行で時計やカウンターをリセットしない。

追加枠の現在値は55要求、入力45,415・出力3,564、完了55・usage不明0。
入力USD10/M・出力USD30/Mという保守的な上限単価でUSD0.561070。
これは請求照合済みの実費ではない。失敗した試験も要求数・usage に含む。
送信前に永続予約し、完全な数値 usage だけを精算する。不明、取消、クラッシュは最悪予約を保持する。
超過を観測した場合は違反を保存して次要求を拒否する。予算8テスト、meter5テストを追加した。

## 判定

| 項目 | 結果・証拠の範囲 |
| --- | --- |
| OA-01/02/03/05 | 非stream・stream・複数ターン・ツールなしが成功。原本 SQLite 復元と別JVM再送も成功 |
| OA-04 | System と競合する合成 User 指示に対し System の機械判定値を保持 |
| OA-06/07 | 明示 output bound と low/medium effort を指定し実APIが受理。枯渇試験 output32を観測 |
| OA-08/09 | 通常完了と `LENGTH` を区別し input/output/total/cached/reasoning を正規化。欠損は null のまま |
| OA-10/11 | JSON Schema と JSON Mode。整数42、余分なfieldなしを検査 |
| OA-12 | 所有する32×32の合成赤画像を RED と認識 |
| OA-13 | 自然な refusal を誘発せず既存 transport/replay fixture の拒否・callback非実行契約で補完 |
| OA-14 | 実APIの上限32で `LENGTH`、usage output32。部分tool/reasoningの拒否と既存回復はfixtureで補完 |
| encrypted replay | 原本2596bytesの暗号化推論を保存し別JVMから1item再送、HTTP200・計算結果7418。復号・改変なし |
| Function Calling | 実ToolCatalog、READ権限、BoundedToolLoop、Run予算、payload保存を経由。決定的な計算課題成功 |
| 動的選択 | 実Core・ToolDiscovery・SQLite。32→33公開、専門tool1回、metadata永続化。初回成功後に独立3試行も成功 |
| 承認再開 | 実Project/Session/RunRegistry/RunService/ApprovalContinuation、LOCAL_WRITEポリシー。中断→正常終了→別JVM承認→完了。成功prefix1回、書込1回 |
| 承認拒否 | 別の実承認待ちを別JVMで拒否。DENIED/CANCELLED、書込なし、prefix1回、追加API要求なし |
| Checkpoint | 実Core、PersistentCheckpointService/Repository。TOOL境界の耐久保存後に正常終了。別JVM再開、previousRun一致、成功prefix1回 |
| 異常系 | 認証、rate、timeout、切断、cancel、SDK例外、不完全応答、欠損payload、異endpoint、GC保護、引数/未公開tool拒否を既存fixtureで補完。意図的な過負荷は行わない |

Reasoning Summary は `auto` 指定・応答受理と計算正答までを確認した。
summary の本文生成有無や有用性を独立に採点した結果ではない。
公開上限128は検証構成の許容値であり、この実試験で128個の同時公開限界を測定したわけではない。
並列call、複数item、LRU、元snapshot、metadata、必要参照GC保護の異常境界は既存169 replay契約で補完する。

## 継続性の限定評価

事前に3試行と固定し、同じ completed history に対し A 原本の正規再送と
C 既存 `LlmConversationCompressor` の実API要約後の継続を比較した。
双方3/3で、ランダム識別子と7417→7418の数値状態を保持した。
Aの最終要求 input332/337/368、output37/39/36、1.566/1.260/1.664秒。
Cの最終要求 input178/182/187、output37/39/36。要約と継続の合計4.378/3.585/4.413秒。
Cには別の要約要求・usageも必要であり、最終要求だけを用いて総費用の削減を主張しない。

Dは実ToolDiscoveryで別の識別子を読む独立3試行、すべて成功・専門tool1回。
A/Cと同じ課題を用いた品質差の比較ではない。
Bの推論省略対照は未実施。最新の公式仕様は対応モデルで完全文脈の正規再送を案内しており、
このモデルに対する省略条件を確定できていない。厳密再送の本番経路へ省略fallbackは追加しない。
少数・容易な合成課題であり、推論再送の品質向上、長時間Runの品質、統計的有意差は認定しない。

最初の見積り21要求に対し動的検索が追加往復し、完遂したブロックは25要求だった。
承認された全体200要求は超えていない。以後の同シナリオは30要求の計画とし、D各Runの既存予算を6要求に制限する。

## 失敗を含む記録

実HTTPは追加55件すべて200だが、Core組立・判定で5回の失敗を記録している。
3回はハーネスが既存 `RunAwareToolCallingAdvisor` を組み込み忘れ、Springの追加tool loopが動いたため。
既存本番構成は正しく、fixtureで再現後に同じadvisorを組み込んだ。
1回は試験側が出力枯渇を例外のみと期待した判定誤り。既存契約はLENGTHで、上限だけ再試行して成功。
1回は要約用の未束縛optionsではなくRunのreplay権限付きoptionsをハーネスが渡したため。
HTTP前に正しく拒否された。既存要約と同じ未束縛optionsに直して3試行を完遂した。
これらをRei本体の新規障害とは分類しない。失敗分のusage・ログを削除しない。

原本restore時の読取cursor競合は初期段階でfixture再現・修正済み。
本体の契約を緩和した修正はない。

## 性能と限界

初期streamの可視本文TTFTは1521.189msの1標本。
追加枠wire JSONは累積428,075bytes、最大21,075bytes。
HTTP header到着240.387〜4124.062msはTTFTとは区別する。
追加試験終了時、payload.db471,040bytes、checkpoint.db73,728bytes、discovery.db61,440bytes、WALファイルなし。
これはDB全体のサイズであり、payload本文の合計byte数そのものではない。
tool時間とpayload保存/復元時間の分離計測、heap傾向、長時間の圧縮・参照GC評価はPhase9に残る。
短い入力・cached0からキャッシュの不具合や実用性能を判断しない。

## 再現と次工程

`scripts/run_responses_acceptance.ps1` は明示承認switchが必須で、固定した接続先・モデル・追加枠だけを扱う。
既存の非空ディレクトリに所有markerがなければ拒否する。台帳を削除して枠を再開しない。
利用前に `-Pfull test-compile dependency:build-classpath -Dmdep.outputFile=target/live-classpath.txt` でクラスを準備する。
秘密値は環境変数だけへ設定し、コマンド引数へ記載しない。例:

```powershell
./scripts/run_responses_acceptance.ps1 -Target openai -Action core -EvidenceRoot ABSOLUTE_OWNED_DIRECTORY -ApprovedExtendedBudget
```

writer/readは同じ証拠ディレクトリで各一度、別JVMとして順に実行する。
同じwriterの繰返しは副作用の重複を防ぐため拒否する。
全回帰・品質・PRレビュー・最新SHA CI・通常マージ・merge SHA CIが次工程。
最新のローカル関連回帰は370件すべて成功、failure/error/skip0（architecture7件を含む）。
新規テストは28件。Python計画検査10件は別集計。全Backend CIの結果にこの件数を代用しない。
ローカル互換サービスの先行結果はPhase8で独立評価し、DeepSeek公式API成功へ読み替えない。
本番gateはPhase9と接続先別受入が完了するまで閉鎖する。

公式根拠: [OpenAI reasoning](https://developers.openai.com/api/docs/guides/reasoning)、
[GPT-5.6 Sol](https://developers.openai.com/api/docs/models/gpt-5.6-sol)。
