# 自律機能の実装状況（2026-10-04）

基準: main `b37ac23`。コード・既存テスト・docs・merge 履歴を照合した。
「部分実装」は関連部品が存在することを意味し、候補全体の完成を意味しない。

| Priority | Feature | Initial status | Final status / 根拠 |
|---|---|---|---|
| A1 | Auto Sleep | Not implemented | 本作業で実装。SleepService、AutoSleepService。起動時保存metadataからのbounded候補発見・idle時継続走査に対応。任意cron/日次・zone指定に対応。終了trigger/外部metadata監視はDeferred |
| A2 | Autonomous Planning Loop | Partially implemented | Implemented in this task（最大16ファイルGoal、全件SHA-256一致）。永続 lifecycle/試行/予算、明示起動、既存 Chat/ActionPlan/FIFO の再利用、独立 SHA-256 判定と bounded 継続。Shellの不確定Run手動reconcile・予算保持・in-flight除外に対応。SubAgentのTool cycle/修復/並列の共有LLM予算継承に対応。汎用検証条件/Skill selector等の予算継承/共通token上限/Native復旧UIはDeferred |
| A3 | Trigger / Scheduler | Partially implemented | Implemented in this task（一回限り continuation）。SQLite 永続化、2〜100回のbounded間隔予約（1分〜366日）・cron予約（明示zone、分単位）、明示 activation、Project/Session 固定、atomic claim、既存 FIFO 経由の bounded dispatch・結果履歴。Shellの不確定Run手動reconcile・元予約非再実行・in-flight除外に対応。対象Run終端イベントの一回限りtrigger、WAITING_EVENT/期限/重複抑制、保存履歴のbounded cursor復元に対応。汎用条件watcher/Native復旧UIはDeferred |
| A4 | Waiting / Dependency | Partially implemented | Implemented in this task（管理プロセスの待機）。状態6種、bounded wait、キャンセル、停滞との区別、Shell 表示。汎用 watcher/依存 graph/復元は Deferred |
| A5 | Policy / Permission | Partially implemented | Implemented in this task。能力7種・Chat/SubAgent 境界に加え、永続一回限り承認、Shell/Web の明示決定、Checkpoint の明示 Resume に対応。Native 専用ボタン・SubAgent 承認継承は Deferred |
| A6 | Notification / Escalation | Partially implemented | Implemented in this task（アプリ内確認待ち）。承認要求/Policy拒否/停滞停止/確認済み長時間待機の永続 inbox、重複抑制、Shell/Web の表示・ack。Native専用UI/外部配送/汎用判断待ちは Deferred |
| A7 | Reflection | Partially implemented | Implemented in this task（Goal事実ベース）。期待ファイル条件/保存検証/差分/固定レビュー提案、永続 source 参照、重複抑制、Shell backfill。全Task/意味分析/Memory昇格は Deferred |
| B8 | Activity 週次・月次 | Not implemented | Implemented in this task（保存観測の算術分析）。暦週/月、前期間比較、観測推定/未観測、分類/Project候補/時間帯/日別、Shell。実測生産性・集中/中断・意味的テーマ・専用UIは Deferred |
| B9 | Adaptive Coaching | Partially implemented | Implemented in this task（手動期間分析統合）。ユーザー分類比率基準・観測品質gate・完了期間限定、永続opt-in設定/期間重複抑制/共通cooldown、Shell。既存Behavior通知を維持。自動週月通知/意味的個人化/専用UIは Deferred |
| B10 | Activity × Work Context | Partially implemented | Implemented in this task（保存Event参照）。ActivityのEvent/Session/Turn/Run ID、Work ContextのTOOL出典へのexact project-scoped join、revision/Item/Git snapshot時刻付きShell表示。観測時Git/ファイル/command・意味的task帰属・専用UIは Deferred |
| B11 | Hybrid RAG | Partially implemented | Implemented in this task（opt-in dense/lexical/RRF）。独立候補port、bounded RRF、source/docId境界、既存文書集約/rerank再利用とreranker port、legacy既定維持。元から汎用HTTP rerankは存在。FTS/BM25/学習sparse・評価は Deferred |
| B12 | Semantic Skill Search | Partially implemented | Implemented (opt-in slice)。 name/description/keywords の embedding、keywordとのRRF、既存rerankerを候補絞り込みに統合。既定無効、明示指定優先、障害時keyword fallback。永続index・学習評価はDeferred |
| B13 | Document RAG | Already implemented | Already implemented。VectorDocumentService、SqliteVectorStore、SearchKnowledgeService、CHAT の retrieval が存在。二重実装しない |
| C14 | SubAgent semantic validation | Not implemented | Implemented (evidence-contract slice)。opt-inで実際のTool応答・Run内ID・ハッシュ・引用・SUCCESS required Toolを独立照合。自由文の意味判断/特定引数のrequired taskはDeferred |
| C15 | SubAgent repair/retry | Not implemented | Implemented (opt-in validation repair)。最大3回・共有maxSteps・単一timeout・cancel・元の診断履歴保持。モデル/Tool障害のretry・永続resumeはDeferred |
| C16 | Parallel SubAgent Delegation | Not implemented | Implemented (bounded independent batch)。最大8依頼/worker2/受付1/120秒、事前検査・入力順結果・部分失敗・親cancel・既存Runner再利用。DAG/合意形成/永続復旧/全子token予算はDeferred |
| C17 | External Delegation Phase 2+ | Partially implemented | Implemented (durable review metadata / explicit re-review)。SQLite結果/前回参照、Project/root境界・明示認可・共通予算・結果不明扱い。fix/書込/CLI session resume/複数agentはDeferred |
| C18 | Repository Map | Partially implemented | Implemented (bounded Git/Java AST slice)。package/型/method/import/main、module path・テスト名候補、内容ハッシュ更新、Project境界、読み取りTool。多言語/意味的依存解決/永続索引はDeferred |
| C19 | Change/Test Impact | Partially implemented | Implemented (Java structural candidate slice)。Repository Map再利用、逆import/テスト名候補の推移探索・根拠/不完全性・READ Tool。Git差分自動取得/coverage/完全意味解析はDeferred |
| C20 | Build/Test Failure Diagnosis | Partially implemented | Implemented (deterministic process-log slice)。既存command/snapshotにreported test/cause/source・分類・固定確認手順、認証情報除去・上限・context圧縮保持。完全原因特定/report読取/自動repairはDeferred |
| C21 | Self Patch Review Loop | Partially implemented | Implemented (explicit bounded static verification cycle)。初回test→同一patch確認/静的diff review→最終test、Fix要求・変更/不完全/失敗の完了拒否。意味review/自動fix/全Goal強制統合はDeferred |
| D | Diagram edit / Change Set / Document Agent / Paper E2E | Deferred | Deferred。A〜C の依存基盤と検証を優先 |

## 重複実装を避ける項目

Work Context は `084647a`（PR #31）、Persistent Checkpoint/Resume は `b37ac23`（PR #35）で merge 済み。
対応コードは workcontext/ と checkpoint/、対応テストと実装レポートもある。
Computer Use、SubAgent schema validation、reviewer、OPML、Feed update、Briefing、Reminder、Interest、
Shell Session、path completion、startup project、intervention、compression、Paper Library、Activity 日次、
Agent Event/UI projection、Topic、Core/UI 分離も既存の各パッケージ・テスト・文書を確認した。

## 優先順位と残作業

Auto Sleep、共通 Policy/永続承認、管理プロセス Waiting、永続一回限り・間隔・cron・Run終端イベント Scheduler、アプリ内 escalation は独立実装済み。
ファイルGoalの永続 lifecycle と bounded Planning Loop、Goal事実ベースのReflection、Activity週月の保存観測分析、手動Coachingの期間分析統合、Activity/Work Context保存Event参照、opt-in文書Hybrid RRF、Semantic Skill Searchも実装済み。残件は本表のDeferred項目を優先度順に再評価する。
並行して、不確定 Scheduled Run の復旧、汎用依存監視、Native の承認・確認待ち UI を既存基盤へ統合する。
週月分析は既存の観測事実を集計し、推測や未観測時間を分離する。SubAgent 修復は bounded retry と元 error 保持を先に整備する。

本表の Deferred は実装完了を意味しない。実装を行った機能の検証・Git 結果は別途実装レポートへ記録する。
