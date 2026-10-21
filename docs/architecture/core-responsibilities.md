# coreの責務

分析対象コミット: `bb4f49247370ed3e6cd9cc3ee06209aa060e5075`。再実行方法は [README](README.md)。本番ソースSHA-256: `f62bb18b627dc42fbe5dd660c2f0d77494a2803076390bf44a74a9733df49ab5`。

`core`配下は261ファイル（本番の16.7%）。一枚のdomain層とは扱いません。

| 境界 | ファイル数 |
| --- | --- |
| core | 34 |
| core.actionplan | 5 |
| core.chat | 19 |
| core.checkpoint | 5 |
| core.command | 17 |
| core.completion | 17 |
| core.configuration | 12 |
| core.contextbudget | 30 |
| core.datasource | 4 |
| core.dependency | 15 |
| core.diagnostic | 2 |
| core.execution | 6 |
| core.filesummary | 5 |
| core.policy | 15 |
| core.predicate | 2 |
| core.process | 10 |
| core.project | 10 |
| core.recentchanges | 5 |
| core.relatedgraph | 5 |
| core.searchcache | 5 |
| core.service | 8 |
| core.sqlitevec | 8 |
| core.stagnation | 11 |
| core.taskstate | 6 |
| core.working | 5 |

- `core.chat`: 実行コンテキスト・会話ループ・配線。`application.run`の受付・状態管理と協働。
- `core.command` / `completion`: CLI解釈・補完。UI寄りの責務を含む。
- `core.policy` / `stagnation` / `checkpoint`: 権限、実行進捗、継続コンテキスト。
- `core.project` / `datasource`: Project所有権・保存先。
- `core.contextbudget` / `working` / `recentchanges` / `filesummary` / `relatedgraph`: 文脈組み立てと配信・キャッシュ。
- `core.process` / `service`: プロセス実行とOS連携。
- `core.execution`: バックグラウンド実行の管理と表示・通知への橋渡し。`core.dependency`: 待機条件と依存の永続管理。
- `core.actionplan` / `taskstate`: 実行計画・タスク状態の文脈化。`core.predicate`: 宣言的な条件評価。
- `core.searchcache`: 検索結果キャッシュ。`core.diagnostic`: 失敗診断。`core.sqlitevec`: ネイティブ拡張のplatform判定・取得・ロード。
- `core.configuration`: モデル・DB等の横断配線と設定。
- `core`直下: Tools、文書・レビュー・修復・ファイル操作等のサービス。単一責務ではない。

上記は名称とコードからの責務分類。全クラスのpackageと規模は [集計](package-dependencies.md)、全ソースは [sources.tsv](data/sources.tsv)。core内部の機能間辺も循環検査の対象です。Eゲートの予算はcore外部→coreのクラス辺、内部集中はpackage辺のfan-in/outで別に観測します。
