# 秘書機能の既存実装調査

対象は取得済み `origin/main` の `d7356291fc4fa25509b310e152476de12656b093`。
Phase 1 の実装前に状態・責務・拡張境界を確認した。古い実装報告に記載の制約より、現行コードを優先する。

## 実行・監視・承認

| 領域 | 現行の入口と保存状態 | 秘書側の扱い |
| --- | --- | --- |
| Scheduler | `temporal/PersistentAgentScheduler`。scheduleAfter/At に加え interval/cron/event も存在。SQLite の claim・履歴・Project/Session 単位の実行中 unique index | 周期起動の基盤を共用。古い scheduler ガイドの「cron 未対応」は現行コードと異なる |
| Dispatcher | `AgentScheduleDispatcher`。既定 OFF、既存 Project FIFO と ChatExecutionService を経由。結果不明の RUNNING は自動で再実行しない | 秘書機能を ON にしただけで無条件の予約有効化や不明操作の再実行をしない |
| Planning Loop | `goal/GoalLoopService` / `GoalRepository`。observe →既存 Chat planner →独立検証→予算内継続。段階、待機、完了 gate を保持 | 新たな巨大エージェントループを作らず、提案・許可と実行を分離 |
| Waiting / Dependency | `core/dependency/DependencySpec` / `PersistentDependencyRepository`。FILE、Git、PROCESS、HTTP、USER_ANSWER、GITHUB_PR_MERGED、EXTERNAL_AGENT 等。RUNNING/WAITING/BLOCKED/COMPLETED/FAILED/CANCELLED | 文字列の待機メモだけで条件成立と断定しない。依存 ID とタスクを結ぶ |
| Tool Permission | `ToolPermissionGuard` → `ToolPermissionPolicy`。READ、NETWORK_READ 等の能力分類。所有者・MCP・SubAgent・入力を制限 | 自律性レベルから tool permission を緩和しない。候補承認をツール承認に読み替えない |
| Approval | `ToolApprovalRepository` / `ApprovalContinuationService`。所有者・引数に対応した承認と再開 | 保存済み承認や承認待ちを別案件の一括許可としない |
| Run | `application/run/RunService` / `RunRegistry` と `core/chat/ConversationInputRouter`。受付・キュー・実行・終了を区別 | 約束の存在は Run の作成を意味しない。成功メッセージだけで約束を DONE にしない |
| 完了検証 | `FileGoalVerifier` / `GoalCompletionGate`。ファイル SHA、JSON predicate、テスト・成果物・review receipt 等を独立検証 | 自由文の「完了」を検証済みとして保存しない |
| Checkpoint / 再開候補 | `PersistentCheckpointService`、`ResumeCommand`。同じ task と新 Run、Project/root/Session・Git・指紋を照合。UNKNOWN 操作は確認まで制限 | 秘書タスク ID と checkpoint task ID は別の識別子として参照で結ぶ |
| SubAgent | `SubAgentRunner`、並列委譲は共有 worker/queue・期限・親 scope・permission を保持 | 必要な実作業の委譲先。秘書の常時監視のためには起動しない |
| Codex / Claude Code | `externalagent` の task specification と既存委譲、`chatgpt` の app-server 連携 | 既存の実行管理・承認・結果記録を保持。別の実行プロセス管理を作らない |

## 観測・記憶・受付

| 領域 | 現行の入口と制約 | 秘書側の扱い |
| --- | --- | --- |
| Activity Timeline | `activity` の保存観測・意味的 session・work context link | 観測事実と推測を区別し、作業履歴の根拠として参照 |
| Desktop Monitoring | input-aware OS 観測、任意の vision queue、時系列補足。未観測・省略区間は離席等と断定不可 | 未設定・OFF 時は不明として動作を継続。低優先度通知の抑制材料 |
| 長期記憶 / Episode / Hybrid RAG | `memory` と `episode`、vectorstore。検索・経験記憶の責務 | タスクの現在状態・承認・実行履歴をベクトル検索だけに依存させない |
| Auto Sleep | `memory/service/AutoSleepService`。idle・busy・cooldown・予算、保存 Session の発見と終了要求 | タスクの抽出は sleep 完了を待たず、新規の入力だけを扱う |
| Conversation | `SessionLifecycle`、`ConversationAdmissionStore`、`SqliteSessionRepository`、`SqliteConversationTurnStore` | 共通の受理境界を利用。全履歴を毎回モデルへ再送しない |
| 入力 | Shell、Web、Voice は共通 lifecycle/queue。MCP は READ_ONLY の制限付き別受付 | 受理後に新規入力の観測通知を追加し、通常の実行・選択・重複受付契約を変更しない |

## 保存・通知・外部接続

`memory.db`、`memory-consolidation.db`、`vectorstore.db`、`storage.db` は役割の異なる既存 DB。
`MemoryDataSourceConfiguration` の `memoryConsolidationDataSource` は `ReiPaths.memoryConsolidationDbPath()`。
一方、Session/Turn/イベント等の共通移行・バックアップ・writer は `StorageMigrationCoordinator` / `StorageDatabase`。
Phase 1 は後者を port/adapter 経由で使い、保存方式を新設しない。

| 領域 | 現行実装 | 拡張時の注意 |
| --- | --- | --- |
| 設定 | application.yaml、`rei.data-dir`、機能別 ConfigurationProperties / ConditionalOnProperty | 各秘書機能を個別 opt-in。外部設定なしでも起動可能にする |
| Attention | `AttentionRepository`、配信 outbox、SlackNotificationProvider、Shell/SSE の `attention.required` | 永続履歴と重複防止を共用。ack は承認・再開ではない |
| Google | `GoogleOAuthService` が Calendar/Tasks の credential・更新を共有。対話認証と scheduler の非対話更新を区別 | Gmail 等のスコープ追加は明示設定と必要な再認可を伴う。トークンや本文をログへ出さない |
| Google Calendar | `googlecalendar/GoogleCalendarService` / Tools / `/schedule` | 既存予定・タイムゾーン・OAuth を基点に拡張する |
| Google Tasks | `task/TaskService`。外部タスク一覧と更新。数値 ID は外部 ID の hash | ローカル約束台帳の ID・優先度・状態を流用しない。既存利用者の契約を保持 |
| GitHub | `github` に署名検証を伴う webhook、fact repository、notification outbox、state verifier が存在 | webhook が使えないローカル環境には追加の接続方式が必要。既存の事実・冪等キーを再利用 |
| Slack | Socket Mode、admission allowlist、永続受信・継続。人間向け入口は自己投稿・bot/subtype を拒否 | 新しい受付返信や秘書通知が別 bot の入力と誤認されないよう維持。外部 agent の入口は別規則 |
| MCP | 認証付き通常 API とは別の loopback connector、許可 Project、固定 READ_ONLY、明示ツール一覧 | 通常の Spring AI Tools を自動公開しない。候補承認・権限付与をモデル向けに公開しない |

詳細な使い方と Phase 1 の状態モデル・イベントフロー・DB・設定・検証手順は [秘書機能](secretary.md) を参照。
この調査記録は、後続 Phase の接続や実サービス試験が完了したという報告ではない。
