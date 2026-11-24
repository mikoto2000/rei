# Responses API 検証記録

この文書は Phase 6 PR 作成前のローカル実測を記録する。最終全回帰・PR・マージ後 CI の結果は進捗台帳と PR に照合する。

## 接続先別の対応

「fixture」は実装と固定 SDK・所有済み HTTP/SSE/SQLite の検証済みを意味し、実接続や本番有効化を意味しない。
Responses の全経路は本番 gate が閉じた状態。Chat Completions は既存経路を維持するが、任意の互換提供元の能力を保証しない。

| 機能 | Chat Completions | OpenAI Responses | DeepSeek Responses |
| --- | --- | --- | --- |
| 通常会話 | 既存対応・回帰検証 | fixture | fixture |
| Streaming | 既存対応・回帰検証 | fixture（終端検証あり） | fixture（平文 delta/done を含む） |
| Function Calling | 既存対応・回帰検証 | fixture（call_id と item ID を区別） | fixture |
| 動的ツール選択 | 既存対応・120ラウンド | fixture・120ラウンド | fixture・120ラウンド |
| 推論情報の再送 | native ordered reasoning の追加対象外。従来設定に依存 | fixture（所有権付き encrypted） | fixture（所有権付き plaintext） |
| Structured Output | 既存対応・回帰検証 | native text.format fixture、明示能力が必要 | 提供元の JSON Schema の受理は未検証。利用可能扱いしない |
| 画像入力 | 既存対応・回帰検証 | input_image fixture、明示能力が必要 | 接続先モデルの画像受理は未検証。利用可能扱いしない |
| 承認継続 | 既存対応・回帰検証 | fixture・実SQLite | fixture・実SQLite |
| Checkpoint 再開 | 既存対応・回帰検証 | fixture・実別JVM | fixture・実別JVM |
| Goal | 既存対応・回帰検証 | fixture（永続予算・成果物SHA検証） | fixture（永続予算・成果物SHA検証） |
| SubAgent | 既存対応・回帰検証 | fixture（子の権限・文脈） | fixture（子の権限・文脈） |
| Context Compression | 既存対応・回帰検証 | fixture・23回反復 | fixture・23回反復 |
| キャンセル | 既存対応・回帰検証 | fixture（未完了 item から実行しない） | 共通の既存ガード。実接続未検証 |
| 予算管理 | 既存対応・回帰検証 | fixture（回数・既知/不明 token・retry） | fixture（回数・既知/不明 token・retry） |

Hosted Tools、previous_response_id、background mode、サーバー側 context_management は初期対象外。
旧 search の独自ツール会話経路と、native の frequencyPenalty を必要とする UI-TARS 構成は未対応として拒否する。
DeepSeek の暗号化 reasoning / summary、黙示的に無視されるオプションは対応済みと表示しない。

## ローカル性能実測

同一の既存 StagnationChatModel / ToolDiscovery / ContextAssembler を使い、各方式で 120 ツール往復・121 SDK 要求。
60 種の専門ツールを検索して次要求から実行し、60 回の所有済みファイル更新で実際の進捗を証明する。
実業務ツール・実 API は実行しない。公開枠は 31 基本＋searchTools＋4 専門、LRU で入れ替える。
Native だけが各応答に約 16 KiB の推論を持つ。CC はその保存・再送を追加しない既存経路の比較条件。
同一 JVM 内の fixture 実行の一標本であり、順序・GC・JIT・request 記録の影響を含む。
値は性能保証や実 API の優劣を表さない。固定 wall-time の CI 合否閾値は設けず、既存 180 秒期限と機能・容量境界を検証する。

| 計測 | CC | encrypted Responses | plaintext Responses |
| --- | ---: | ---: | ---: |
| 総時間 ms | 9,571.336 | 18,862.866 | 18,423.700 |
| 圧縮回数 | 0 | 23 | 23 |
| 推論 UTF-8 bytes/応答（最小） | 0 | 16,385 | 16,399 |
| 推論 UTF-8 bytes/応答（最大） | 0 | 16,387 | 16,401 |
| unique payload bytes / Run累積 | 0 | 2,141,294 | 2,133,254 |
| payload 件数 | 0 | 267 | 267 |
| SQLite 本体 bytes | 0 | 2,502,656 | 2,510,848 |
| WAL bytes（存在なしは0） | 0 | 0 | 0 |
| HTTP body 累積 bytes | 6,021,884 | 9,533,785 | 9,547,228 |
| 最終 request bytes | 87,087 | 96,836 | 96,989 |
| 正本 trace token 概算（投影前） | 対象外 / 未測定 | 514,267 | 513,567 |
| 最終投影 instructions token 概算 | 19,052 | 22,090 | 22,065 |
| HTTP境界のヒープ標本最大 bytes | 228,954,320 | 165,017,312 | 192,446,288 |
| 実行後ヒープ bytes | 149,841,808 | 127,389,896 | 134,123,000 |
| 単一 save probe μs | 対象外 / 未測定 | 15,010.800 | 14,497.100 |
| 単一 load probe μs | 対象外 / 未測定 | 2,479.200 | 3,628.600 |

token は TokenEstimator.conservative の**概算**で、プロバイダー報告値や正確な tokenizer の実測ではない。
正本 trace は System / tools の定義を除き、最終投影 instructions は host System を含むため、厳密な同一集合比ではない。
同じ定義での効果は全ラウンドの直前 call/output 保持と、繰り返し圧縮の成功で別途検証する。
CC の専用 payload DB 0 は native 保存経路を使わない意味で、既存の全 DB 使用量が0という意味ではない。
DB/WAL は store 接続を閉じた時点の標本。WAL の運用時最大量を証明しない。
save/load は別の小さい所有済みメッセージの単一 probe。大容量 payload の分位点・レイテンシ分布は未測定。
heap は JVM 全体の標本であり、方式ごとの純増や GC 後 live set ではない。

| 未測定 | 理由 |
| --- | --- |
| 実 API Prompt Cache ヒット率 | 実 API 呼び出しの認証・データ・課金許可を取得していない。fixture usage の cached_tokens は仕様検証用の定数 |
| 実 API TTFT / 推論品質 / 意味的継続性 | 同上。fixture wall time で代用しない |
| 本番 DB / WAL・既存会話の移行性能 | 本番データを開かず、migration・既存データ削除を許可されていない |
| 実運用での p95 / peak live heap | ローカル機能 fixture の単一実行は負荷試験・プロファイラー測定ではない |

容量の合否は既存 64 MiB / Run、512 MiB / 全体の上限と、unique payload の線形増加で判定する。
120 ラウンド fixture は Run 累積 8 MiB 未満を検証し、8 MiB/メッセージなど既存の拒否境界は別の quota 試験で確認する。
HTTP 再送量は stateless な履歴投影に依存し、永続化の hash deduplication が通信量をゼロにするとは扱わない。

## 障害と回帰

- 不正な SDK delta / nested item が Spring の集約ログへ渡る前に固定エラーへ正規化されることを実 canary で確認。
- 承認の COMPLETED / DENIED、outer rollback、cleanup I/O / 破損文書 / lookup failure で状態・参照を確認。
- 要約済み履歴の参照解放、hash / scope 不一致時の一括 rollback、他の全 anchor の保持、GC 後の再実行。
- SQLiteFull の注入は所有済み SQLite の max_page_count で行う。実ディスクを満杯にせず、本文・receipt・容量計上の rollback を確認。
- 既存の writer / GC 競合、quota、別 JVM の crash / restart、承認・Checkpoint の残り callback だけの継続を維持。
- retry は既存の上限・token 使用量不明・X-Should-Retry:false を守り、API fallback や副作用の自動再実行を追加しない。

運用・移行・切り戻しと残存保持リスクは [運用手順](responses-api-operations.md) を参照する。
最終件数は最新 head の CI によって確定し、過去の失敗や未測定を成功で上書きしない。


## PR 作成前の検証結果

| 検証 | 実行結果 |
| --- | --- |
| 関連 Maven 回帰 | 55 スイート818件、成功818、failure/error/skip0 |
| 書式修正後の再検証 | 227件、成功227、failure/error/skip0。818件と一部重複するため合算しない |
| architecture | 上記の双方で全7件成功。基準・予算・除外は変更なし |
| 最終 main / test compile | 全1768 main、1385 test ソース成功 |
| package | jar / Spring Boot repackage 成功。配置・起動は行わない |
| Spotless | 全3153 Javaソースの check 成功 |
| Checkstyle | 違反0件 |
| SpotBugs | 指摘0件・解析エラー0件 |

最初の818件実行と次の227件実行の verify は、それぞれ書式チェックで失敗した。
最後に既存書式を適用し、テスト再実行を省略した compile/package/全品質コマンドが成功した。
失敗した verify 全体を成功と記録せず、実際のテスト件数・品質の結果を分けて記録する。
最新 PR head の全プロジェクト回帰 CI が成功する前にマージしない。
