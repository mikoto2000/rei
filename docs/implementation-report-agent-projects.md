# Shell / AgentRun 分離・プロジェクト分離・Event 永続化 実装報告

## 1. 変更前の調査結果

- **Shell input**: `ReiApplication` は JLine で入力後、コマンドを Executor に渡して `EscCancellationMonitor.await` で待機していた。実行スレッドは分かれていたが入力受付は停止していた。
- **AgentRun**: `ChatCommand` は `ChatExecutionService` を同期呼び出ししていた。後者が streaming、キャンセル、output-limit replanning、Run 完了を管理。LLM → Tool → LLM の明示的なループは `StagnationChatModel` に既に存在した。
- **Conversation**: 通常会話の ID は固定の `chat:main`。ChatMemory/Advisor と、JSONL の `ConversationLogStore` があり、検索はログと JDBC を併用していた。
- **Project**: `ProjectService` は current path と行形式の登録一覧を保持し、`/project cd` は登録済みパスへの切り替えのみ。Tool がその mutable なパスを実行途中でも参照していた。Working Set、ActionPlan、TaskState、checkpoint、キャッシュ類は共有インメモリ状態だった。
- **Event**: 型付き `AgentEvent`、プロセス内採番を行う Bus、`DefaultAgentUiProjection`、独立した `ShellAgentEventRenderer` が存在。RunId を持たないイベントもあった。
- **Log / storage**: `ReiPaths` と設定既定値は起動場所の `.rei` を利用。会話ログ、profile log、system log、DB、画像、設定、キャッシュ等が対象だった。
- **Concurrency**: Shell Executor、Reactor boundedElastic、バックグラウンド処理があり、ThreadLocal だけでは非同期境界を越えて所属を伝播できない構造だった。

詳細な調査と TDD の記録は `docs/agent-run-project-state.md` に記載。

## 2. Phase 1

`AgentRunContext`、`UserInterventionQueue`、`ConversationInputRouter`、`AgentRunConfiguration`、`AgentRunScope` を追加。

通常入力は Shell から即座に受理し、IDLE なら Agent 用 Executor に dispatch、既存 Run がある場合はその FIFO mailbox に格納する。picocli の可変コマンドインスタンスを Agent worker と共有せず、入力文字列とコンテキストをコピーする。

適用箇所は Tool result の後、通常 LLM 応答の後、および最終応答後の Run 完了判定。実行中の request/tool は追加入力ではキャンセルしない。追加指示は `UserMessage` として ChatMemory と会話ログに保存する。received/applied は同じ入力 ID と RunId を持つ型付きイベントとして観測できる。

mailbox の受付と close 判定は同じ排他境界を使い、完了直前の入力は既存 Run が受け取るか、次の Run として受理する。Tool と Advisor の境界では immutable なコンテキストを明示的に引き継ぐ。`/cancel` は通常入力とは別コマンド。

Phase 3 の結合検証では、非表示プロジェクトの renderer 状態保持、入力編集中の出力バッファ上限、Agent の直接標準出力を Shell 出力から分ける `AgentConsoleSession`、履歴書き込み失敗時の確実なキャンセル状態 cleanup も補強した。

**テスト**: FIFO、close/enqueue の競合、IDLE/RUNNING routing、Tool 中の入力、safe point、履歴保存、picocli の非同期受付、既存 cancel/streaming/stagnation/replan を検証。各単位で RED → GREEN、各 Phase の最後に全体テストを実行。

## 3. Phase 2

### 保存先

この Windows 環境の既定値は `C:\Users\mikoto\AppData\Local\Rei`。

- Windows: `%LOCALAPPDATA%\Rei`
- Linux: `$XDG_DATA_HOME/rei`、未設定時 `~/.local/share/rei`
- macOS: `~/Library/Application Support/Rei`
- 上書き: `REI_DATA_DIR` または Java の `-Drei.data-dir=...`

```text
<rei-data-dir>/
  application.yaml
  additional-system-prompt.md
  projects.json
  memory.db / その他の global DB・設定・キャッシュ
  logs/rei.log
  projects/<uuid>/
    conversations/<date>.jsonl
    working-set/files.json
    logs/activity.jsonl
    state/last-run.json
    events/events.jsonl
```

### Registry / scope

`ProjectRegistry` は JSON の `projects` 配列に `id`・`name`・`path` を保存する。ID は UUID、path は canonical path。書き込み成功後に切り替えを公開し、明示的な `relocate` API では ID を維持する。

`ProjectContext` と `ProjectStorage` が namespace を定義する。通常会話の論理 ID は `chat:main` のまま、内部キーは `project:<uuid>:chat:main`。`/project cd` は未登録なら登録し、会話・Working Set・検索・activity log の scope を切り替える。

Run の保存先は RunContext の ProjectId/ConversationId から決定する。通常入力の mailbox もパスではなく永続 ProjectId で識別する。B で `/cancel` しても A の Run はキャンセルしない。

`ProjectBeanScope` により、Working Set、ActionPlan、TaskState、checkpoint、file summary、recent changes、search cache 等の既存サービス API を維持しながらプロジェクトごとにインスタンスを分けた。Working Set と last-run state は個別ファイルから復元する。project activity と system log の保存先を分離した。

### 既存データ

旧 `.rei` の削除・自動書き換え・自動インポートは行わない。旧 `chat:main` の所属プロジェクトを推測して統合する処理も行わない。設定や Skill/MCP 定義の手動コピー、旧 DB の保持、会話 namespace の注意点は設計メモに記載した。旧ログを型付き Event に変換する migration は未実装。

**テスト**: identity の reload/relocate、未登録 cd、A→B→A、Store 分離、history search、Spring scoped proxy、Working Set のファイル復元、イベント所属、B からの cancel、非同期 Advisor/ToolContext、global default path を検証。全体テスト成功後に Phase 2 をコミット。

## 4. Phase 3

`ProjectAgentEventStore` と subscriber を追加。完成済み通知文字列を保存せず、型付き payload、ProjectId、RunId、timestamp、type、sequence 等を持つ Event を JSONL に保存する。既存 Jackson を使用し、大きな依存の追加はない。payload 型は既存 sealed interface の許可型に限定して復元する。

永続ログの sequence は Project ごとに単調増加し、再オープン時は末尾から再開する。既存 Bus の `lastSequence()` は互換性のためプロセス全体の sequence として維持している。永続 sequence と Bus sequence は用途が異なる。

Bus は並行発行と再入発行を配送キューで順序付けする。Store 障害は Bus の既存 listener 例外隔離で warning として観測し、Agent や他 listener を停止しない。途中で切れた末尾行は警告付きで飛ばし、次の完全なイベントを読めるようにする。

`ProjectShellActivity` はライブと復元を `ShellAgentEventRenderer` に接続する。通常の cd は Conversation/Working Set/last run のサマリーと最近の活動のみ。本文 delta を会話全文として再生しない。既定は直近 20 Event、設定キーは `rei.events.recent-limit`（1〜1000）。最近の取得はファイル末尾から行い、全ログを走査しない。将来の全履歴再生向けに `readAfter` によるページ取得を分離した。

非表示プロジェクトの Event も renderer の内部状態には反映するが、出力は選択中プロジェクトに限定する。実行中のプロジェクトへ戻ったときも streaming の続きを表示できる。`DefaultAgentUiProjection` のモデルを置き換えず、同じ AgentEvent を共有する。

**テスト**: Project ごとの永続採番、型の round-trip、live/persisted の同一 renderer、最近 N 件、末尾破損、保存失敗の隔離、再入/並行配送順、cd 接続、表示 scope、stream 再開、実際の ChatClient と停止可能な偽 Tool による A→B 切り替え中の保存先固定を検証。

## 5. 主要変更ファイル

| 主なファイル/領域 | 変更 |
|---|---|
| `ReiApplication`, `ChatCommand`, `CancelCommand` | 入力と Run の分離、cancel、JLine 接続 |
| `core/chat/*`, `StagnationChatModel`, `RunExecutionContext` | immutable context、mailbox、safe point、終了競合 |
| `ReiDataDirectory`, `ReiPaths`, `ExternalConfigSupport` | global 保存先 |
| `core/project/*` | Registry、ProjectContext、scoped services、Run state |
| `ConversationLogStore`, `ConversationHistorySearchService`, `ConversationIds` | 会話と検索の namespace |
| `WorkingSet`, 各 state の Configuration | scope と Working Set persistence |
| `RunScopedAdvisor`, `ToolEventCallbackDecorator`, `AgentEventChatModel` | 非同期境界の所属引き継ぎ |
| `AgentEvent*`, `InMemoryAgentEventBus`, `ProfileEventLogStore` | 所属と順序、activity log |
| `ProjectAgentEventStore`, `ProjectAgentEventSubscriber` | 構造化 Event の永続化 |
| `ProjectShellActivity`, `ShellAgentEventRenderer`, `JLineShellEventOutput`, `AgentConsoleSession` | live/restore、streaming、入力保護 |
| `README.md`, `DEVELOP.md`, `docs/*` | 操作、保存先、移行制限、検証記録 |

## 6. テストコマンド

各単位の `-Dtest=...` で RED/GREEN を確認した。記録は `target/phase1-*.log`、`phase2-*.log`、`phase3-*.log`。

Phase ごとに次の全体テストを実行した（Phase 1 は `rei.data-dir` 指定なし）。sqlite-vec を GitHub から取得するテストはネットワークアクセスが必要だった。

```powershell
.\mvnw.cmd -o '-Dmaven.repo.local=C:\Users\mikoto\.m2\repository' '-Drei.data-dir=F:\project\rei\target\test-rei-data' test
git diff --check
```

Phase 1/2 の全体テストは成功。最終全体テストは **1,380 件、Failures 0、Errors 0、Skipped 0、BUILD SUCCESS**。結果は `target/final-all-tests.log` に記録。`git diff --check` も成功。実 LLM サーバーを用いた手動の対話操作は実施していない。結合テストは実 ChatClient と controllable fake を使用した。

## 7. コミット

- `34cb685` — `feat: allow user intervention during agent runs`
- `dec46f2` — `feat: scope rei state by project`
- Phase 3: `feat: persist project agent events`（commit hash は完了メッセージおよび git log を参照）。

## 8. 残課題・意図的な範囲

- 旧 `.rei` の自動 migration と、ディレクトリ移動の自動検出は未実装。旧データは保持する。
- v1 の Agent executor はプロジェクトをまたいでも直列。同じ Project に複数 Run を無条件で並行実行しない。
- EventStore と Registry は単一 Rei プロセスでの利用を想定。同じ保存先への複数プロセス同時書き込みの調停、ログの retention/rotation は未実装。
- ActionPlan/TaskState/checkpoint/cache は project scope のインメモリ状態。これらすべての再起動後の復元を追加したわけではない。Working Set、会話、last-run snapshot、Event audit はそれぞれの保存先を利用する。
- 完全な Event Sourcing、GUI、新しい full-replay コマンドは追加していない。
