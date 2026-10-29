# ロングランタスク実行基盤

## 基準と責務

Phase 0 の調査基準は origin/main `f27def79`（2026-10-29）。既存の Run、Goal、承認、
Project queue、Checkpoint を置き換えず、対象と確定成果を Run の寿命から独立させる。

| 既存機能 | 再利用する保証 | 補う境界 |
| --- | --- | --- |
| GoalRepository / GoalLoopService | 論理目標、実行試行、共有予算、独立検証 | 全対象の列挙完了と WorkItem 完了の検証 |
| GoalStageService / GoalStageVerifier | 工程の依存と証拠検証 | 局所・ファイル統合・横断工程の結果参照 |
| PersistentAgentScheduler | 条件付き claim、旧 callback の拒否 | 副作用不明の予約は自動再実行しない方針を維持 |
| GoalWaitService / PersistentDependencyRepository | 永続待機、条件の再確認 | 待機を Worker 占有や進捗と混同しない |
| PersistentCheckpointService / Repository | PID・起動時刻による所有権、復元、UNKNOWN 操作 | 唯一の結果索引にしない |
| ReviewCoverageLedger / ReviewTools | Fetched/Delivered/Reviewed、版・引用検証、原子的確定 | Run 参加者上限、全件対象の登録保証 |
| RunRegistry / cancellation / ProjectRunQueue | 終端状態、キャンセル、cleanup までの排他 | 親作業の取消と次項目の claim を連動 |
| SubAgentRunner | 子の実行・共有 Run 予算・承認境界 | 作業項目を明示して結果を引き継ぐ |
| ContextAssembler / Compression | hard token budget、配信確認 | 確定成果を再投入せず未完了項目から復元 |
| ProgressEvaluator / RunExecutionContext | 情報・範囲・成果・工程の区別、永続復旧予算 | 生存、確定処理数、品質を別の観測値にする |
| StorageDatabase / MigrationCoordinator | FULL 同期、writer 直列化、移行・backup・OS 排他 | 正規化テーブルと移行検証を追加 |
| RepositoryMapService / ChangeTestImpactService | シンボル・関係・変更影響の情報 | Snapshot と一致する情報のみ分割・横断の補助に使う |

TaskManagerService は既存状態を投影する画面用モデルであり、新しいスケジューラではない。
Goal ID がある場合はその ID を親目標として関連付ける。通常会話にも永続作業が必要なため、
Snapshot/WorkItem の集約 ID は Goal の有無に依存させない。既存の Checkpoint task ID、
ReviewJob ID は保持し、関連表で集約へ結ぶ。既存 ID の意味や JSON 形式を変更しない。

## 現状の再現可能な制約

- findFile は既定50・最大500件の検索であり、列挙完了証明ではない。
- beginReview は64ファイル・抽出テキスト8MiBまで。ジョブJSONは2MiB、全体64MiBまで。
- requireComplete は登録済みジョブだけを検証し、未登録のファイルを検出できない。
- resume/grant が同じ participants を増やすため、合計64参加者を超えると再開できない。
- レビュー結果と完了状態は同じJSON・同じSQLite transactionで確定する。再実装しない。
- ジョブ保存と Checkpoint の REVIEW_JOB_REFERENCE 保存は別境界。新しい作業では直接の
  Task 関連を保存し、Checkpoint の参照だけを再開の入口にしない。
- reviewStatus は対象全ファイルを再読込する。固定 Snapshot の再利用で反復コストを抑える。

LongRunBaselineTest は既存台帳について、中央の未処理範囲、再オープン後の確定成果、
同じ結果の再送、キャンセル後の遅延結果、版変更後の完了拒否をモデルなしで確認する。
既知の未実装要件を成功扱いにするテストは追加せず、対応 Phase の Red として追加する。

### レビューへのアクセス拒否

既存レビューへのアクセスは Project・会話・Run が参加者に登録されている場合に限る。
`checkpointShow` で古いレビューIDを読んでも権限は移譲されない。
`REVIEW_ACCESS_DENIED` は型付きの失敗として通知し、モデルには `success=false`、
エラーコード、安全な拒否理由と復旧手順を返す。`tool.failed` を保持し、後続の独立した操作は続行できる。
所有者・参加者・対象ファイル・評価内容は失敗結果へ含めない。

同じIDの再試行やRun情報の書き換えは行わない。既存作業の引き継ぎが必要なら、利用者の明示的な
再開依頼に基づく `checkpointResume` を使う（新しいRunの予約であり、現在のRunへの権限付与ではない）。
現在の依頼で新たにレビューする場合は、現在のProject内の
対象と評価基準で `beginReview` を実行する。拒否されたレビューの完了状態は確認済みとして扱わない。
アクセス拒否を受けたことによる自動の権限付与や参加者追加は行わず、キャンセル・実行停止は伝播する。

`ReviewAccessRecoveryTest` は Project・会話・Run の不一致での拒否、元レビューと参加者の不変、
拒否後の新規レビュー開始、異なるProject・会話への再開拒否を実ツール境界で検証する。

## データモデル

同じ管理 SQLite 内に追加し、既存テーブルの読み取り・再開を維持する。

| 概念 | 保存内容と制約 |
| --- | --- |
| Task | 集約ID、Project/Session/root、任意Goal ID、状態、取消、消費予算。Run終了で削除しない |
| TaskSnapshot | Task ID、モード、列挙状態、継続位置、エラー、対象確定の世代 |
| Snapshot file | Snapshot ID＋正規化相対パスを一意化。内容、SHA-256、除外理由。エラーも保存 |
| WorkItem | Task/Snapshot、版・範囲・観点・階層の一意キー、状態、必須条件、親項目 |
| TaskAttempt | Task＋Run、開始・終了と消費量。参加者数の上限とは分離 |
| Lease | WorkItem の現在の所有者、世代、期限、生存時刻。claimと確定時に比較 |
| Result | WorkItem＋対象版の一意結果、評価・指摘・証拠。項目完了と同一transaction |
| Checkpoint | 任意の高速復元情報。失っても Snapshot/WorkItem/Result から再開可能 |

固定 Snapshot は変更後も元内容を読めるよう内容自体を保存する。Git HEAD は説明用の版情報で、
未コミット内容の復元根拠にしない。通常ファイルとリンクを区別し、Project 外へ辿らない。
大きな集合は索引付き行と keyset cursor で扱い、全体JSONや全件LLM prompt を作らない。

## 不変条件と検証計画

| 不変条件 | Phase / 検証 |
| --- | --- |
| 列挙未完了、上限、アクセス拒否、未走査を成功にしない | 1: 10,001ファイル、深い階層、中断再開、エラー注入 |
| 対象集合の確定と処理完了を区別し、除外には理由がある | 1: 未確定・空集合・除外の独立ゲート |
| TaskはRunをまたぎ、Checkpointなしで結果を復元できる | 2: 101回再開、参照欠落、プロセス異常終了 |
| 完了項目には同じ版の一意結果が存在する | 2: 結果/完了transactionの前後障害、再送 |
| 古い所有者、期限切れ遅延応答が結果を確定できない | 3: 競合claim、世代交替、heartbeat停止 |
| 外部副作用不明の処理を期限切れだけで再実行しない | 3: 実行分類別の復旧 |
| 取消後claim禁止、明示再開まで取消維持 | 3: 同時取消・再起動・旧callback |
| 予算超過は成功でなく、再開で予算を回復しない | 3: 永続予約・再試行/再計画上限 |
| 項目・結果・指摘を有界ページで取得する | 4: 10,001項目、欠落・重複、旧データ互換 |
| 確定項目をモデルへ再投入しない | 4: 再開前後の再処理数・読込数 |
| 局所・ファイル・横断工程の必須条件を独立検証する | 5: 範囲の穴、根拠不一致、横断未完了 |
| LLMの自己申告だけでは成功にしない | 全Phase: ホスト検証、Goal/Stage統合 |

認証失敗、権限不足、取消、予算超過は無限リトライしない。再実行許可は作業の属性であり、
fencingだけで外部副作用の重複を防げるとは扱わない。失敗を理由なしSKIPPEDに変えない。

## 変更対象と測定

Phase 1 は review パッケージに対象・Snapshotの保存とTool入口、storageに移行を追加する。
Phase 2 は作業・結果・Run関連を永続化し、既存 ReviewJob の履歴と現在権限を分離する。
Phase 3 は同じ実行器のclaim・heartbeat・failure・cancel・予算を追加する。
Phase 4 は索引・keysetページ・差分保存・集計と読み取りの再利用を整える。
Phase 5 は分割・階層依存・引用検証・Goal/Stageゲートと統合レポートを追加する。
CLI/HTTP/MCPは既存の入口・承認を維持し、明示した追加操作だけを公開する。

性能は同じJDK25・同じSQLite・同じ10,001ファイルfixture・実LLMなしで比較する。
対象登録、結果確定、未処理ページ、再開の処理時間、DB/WALバイト数、再処理数を記録する。
JVM warmup・生成時間と処理時間を分離し、ハードな時間閾値でCIを不安定にしない。
旧台帳は64ファイル以下の同条件で比較する。10,001ファイルの単一旧ジョブは上限で拒否される
ため、同じ処理を完遂したという比較値を捏造しない。メモリ・LLMトークンは未測定なら明記する。

## 実装進捗

| Phase | 状態 | ブランチ | PR / マージ |
| --- | --- | --- | --- |
| 0 | 調査・基盤テスト実施中 | codex/long-run-phase0 | 未作成 |
| 1 | 未着手 | — | — |
| 2 | 未着手 | — | — |
| 3 | 未着手 | — | — |
| 4 | 未着手 | — | — |
| 5 | 未着手 | — | — |

各PhaseのRed/Green、コミット、検証結果と残件は docs/implementation-report-durable-task-execution.md
へ追記する。後続Phaseは直前Phaseのマージ済みmainから開始する。
