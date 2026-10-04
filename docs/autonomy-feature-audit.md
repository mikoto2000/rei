# 自律機能の実装状況（2026-10-04）

基準: main `b37ac23`。コード・既存テスト・docs・merge 履歴を照合した。
「部分実装」は関連部品が存在することを意味し、候補全体の完成を意味しない。

| Priority | Feature | Initial status | Final status / 根拠 |
|---|---|---|---|
| A1 | Auto Sleep | Not implemented | 本作業で実装。SleepService、AutoSleepService。起動時全 Session 走査は未対応 |
| A2 | Autonomous Planning Loop | Partially implemented | Deferred。core/actionplan、taskstate、stagnation、BoundedToolLoop はあるが、Goal の永続 lifecycle と独立した完了判定ループはない |
| A3 | Trigger / Scheduler | Partially implemented | Deferred。temporal/InMemoryAgentScheduler は予約をメモリ保存するだけ。汎用 dispatch・永続 schedule・history はない。Feed/Reminder/Topic の個別 timer は存在 |
| A4 | Waiting / Dependency | Partially implemented | Deferred。BackgroundProcessManager と TaskState.BLOCKED は存在。汎用 WAITING/依存 graph/待機と停滞の統合はない |
| A5 | Policy / Permission | Partially implemented | Deferred。SubAgentToolPolicy、Computer Use SafetyPolicy、ExternalAgentAuthorization は個別実装。共通 capability 判定はない |
| A6 | Notification / Escalation | Partially implemented | Deferred。Agent Event、Shell projection、Sound、SSE、Behavior 通知は存在。汎用判断待ち・permission・長時間待機 escalation はない |
| A7 | Reflection | Partially implemented | Deferred。Sleep の LESSON/PROCEDURE、Work Context の決定・障害整理は存在。全 Task の期待差分/改善案を保存する独立した仕組みはない |
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

最初に既存 Sleep を再利用できる Auto Sleep を独立実装する。
その後は共通 Policy → Waiting と stagnation の統合 → 永続 Scheduler/dispatch → escalation → Planning Loop の順が妥当。
Policy の approval UI と永続 Schedule の所有 Project/Session 境界を整えてから自律的な Tool 実行を増やす。
週月分析は既存の観測事実を集計し、推測や未観測時間を分離する。SubAgent 修復は bounded retry と元 error 保持を先に整備する。

本表の Deferred は実装完了を意味しない。実装を行った機能の検証・Git 結果は別途実装レポートへ記録する。
