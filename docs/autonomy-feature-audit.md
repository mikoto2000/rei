# 自律機能の実装状況（2026-10-04）

基準: main `b37ac23`。コード・既存テスト・docs・merge 履歴を照合した。
「部分実装」は関連部品が存在することを意味し、候補全体の完成を意味しない。

| Priority | Feature | Initial status | Final status / 根拠 |
|---|---|---|---|
| A1 | Auto Sleep | Not implemented | 本作業で実装。SleepService、AutoSleepService。起動時全 Session 走査は未対応 |
| A2 | Autonomous Planning Loop | Partially implemented | Implemented in this task（ファイルGoal）。永続 lifecycle/試行/予算、明示起動、既存 Chat/ActionPlan/FIFO の再利用、独立 SHA-256 判定と bounded 継続。汎用検証条件/独立LLM予算継承/不確定Run復旧UIは Deferred |
| A3 | Trigger / Scheduler | Partially implemented | Implemented in this task（一回限り continuation）。SQLite 永続化、明示 activation、Project/Session 固定、atomic claim、既存 FIFO 経由の bounded dispatch・結果履歴。cron/汎用 trigger/不確定 Run 復旧UI は Deferred |
| A4 | Waiting / Dependency | Partially implemented | Implemented in this task（管理プロセスの待機）。状態6種、bounded wait、キャンセル、停滞との区別、Shell 表示。汎用 watcher/依存 graph/復元は Deferred |
| A5 | Policy / Permission | Partially implemented | Implemented in this task。能力7種・Chat/SubAgent 境界に加え、永続一回限り承認、Shell/Web の明示決定、Checkpoint の明示 Resume に対応。Native 専用ボタン・SubAgent 承認継承は Deferred |
| A6 | Notification / Escalation | Partially implemented | Implemented in this task（アプリ内確認待ち）。承認要求/Policy拒否/停滞停止/確認済み長時間待機の永続 inbox、重複抑制、Shell/Web の表示・ack。Native専用UI/外部配送/汎用判断待ちは Deferred |
| A7 | Reflection | Partially implemented | Implemented in this task（Goal事実ベース）。期待ファイル条件/保存検証/差分/固定レビュー提案、永続 source 参照、重複抑制、Shell backfill。全Task/意味分析/Memory昇格は Deferred |
| B8 | Activity 週次・月次 | Not implemented | Deferred。ActivityTimeline/DailySummaryService/TrendSummaryPolicy は日次または指定範囲。週月比較 API はない |
| B9 | Adaptive Coaching | Partially implemented | Deferred。activity/behavior は基準・通知 cooldown・disable 対応。週月分析との統合はない |
| B10 | Activity × Work Context | Partially implemented | Deferred。Activity の foreground/project と Work Context の Git/Turn metadata は存在。共通 ID での相関はない |
| B11 | Hybrid RAG | Partially implemented | Deferred。SqliteVectorStore は dense/lexical/adjacent chunk/加重 hybridScore 対応。独立 dense+sparse の merge/RRF/reranker interface はない |
| B12 | Semantic Skill Search | Partially implemented | Deferred。SkillCandidateSelector は keyword/name/description の文字列 scoring。embedding 検索はない |
| B13 | Document RAG | Already implemented | Already implemented。VectorDocumentService、SqliteVectorStore、SearchKnowledgeService、CHAT の retrieval が存在。二重実装しない |
| C14 | SubAgent semantic validation | Not implemented | Deferred。SubAgentResultValidator は JSON Schema。実行証跡と回答の意味整合性は検証しない |
| C15 | SubAgent repair/retry | Not implemented | Deferred。SubAgentRunner は validation failure を FAILED として返す。repair loop はない |
| C16 | Parallel SubAgent Delegation | Not implemented | Deferred。単一 delegateTask が境界。単一呼び出しの信頼性改善後に検討 |
| C17 | External Delegation Phase 2+ | Partially implemented | Deferred。read-only Codex review は存在。fix/re-review/persisted session/複数実装はない |
| C18 | Repository Map | Partially implemented | Deferred。RelatedFileGraph、FileSummary、Working Set は存在。リポジトリ全体の symbol/module index はない |
| C19 | Change/Test Impact | Partially implemented | Deferred。RelatedFileGraph、テスト分類・性能改善は存在。変更から affected test を返す仕組みはない |
| C20 | Build/Test Failure Diagnosis | Partially implemented | Deferred。process の exit/status/log と Tool failure event は存在。failed test/exception/cause/next action の診断モデルはない |
| C21 | Self Patch Review Loop | Partially implemented | Deferred。reviewer SubAgent、External review は存在。実装→test→自身の diff review を強制するループはない |
| D | Diagram edit / Change Set / Document Agent / Paper E2E | Deferred | Deferred。A〜C の依存基盤と検証を優先 |

## 重複実装を避ける項目

Work Context は `084647a`（PR #31）、Persistent Checkpoint/Resume は `b37ac23`（PR #35）で merge 済み。
対応コードは workcontext/ と checkpoint/、対応テストと実装レポートもある。
Computer Use、SubAgent schema validation、reviewer、OPML、Feed update、Briefing、Reminder、Interest、
Shell Session、path completion、startup project、intervention、compression、Paper Library、Activity 日次、
Agent Event/UI projection、Topic、Core/UI 分離も既存の各パッケージ・テスト・文書を確認した。

## 優先順位と残作業

Auto Sleep、共通 Policy/永続承認、管理プロセス Waiting、永続一回限り Scheduler、アプリ内 escalation は独立実装済み。
ファイルGoalの永続 lifecycle と bounded Planning Loop、Goal事実ベースのReflectionも実装済み。次はActivity週月分析を優先する。
並行して、不確定 Scheduled Run の復旧、汎用依存監視、Native の承認・確認待ち UI を既存基盤へ統合する。
週月分析は既存の観測事実を集計し、推測や未観測時間を分離する。SubAgent 修復は bounded retry と元 error 保持を先に整備する。

本表の Deferred は実装完了を意味しない。実装を行った機能の検証・Git 結果は別途実装レポートへ記録する。
