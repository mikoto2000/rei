# Summarize / Image バックグラウンド実行

## 調査結果

変更前の `/summarize <URL>` は `SummarizeCommand` から要約サービスを同期呼び出しし、現在の会話に user/assistant を追記して結果を出力していた。専用の直前結果ファイルはなかった。画像生成は `/image generate ...` から同期呼び出しし、設定された画像ディレクトリまたは指定ファイルに保存していた。画像結果を会話履歴に追加する仕様はなかった。

Shell の slash command は `ReiApplication.executeInterruptibly` の実行待ちを通る。一方、chat は `ConversationInputRouter` が既存の managed executor に投入する。Router 内の concurrent map が実行中 AgentRun の正本であり、プロジェクト単位の mailbox と successor により同一プロジェクト最大1 AgentRun を保証していた。`AgentRunContext` は ProjectId / ConversationId / project root を保持する。`ProjectRegistry` は永続IDを管理し、`ProjectService` は Shell の選択中プロジェクトと実行時 scope を区別する。

`ActiveRunDisplay` と `/runs` は Router の一覧を参照する。`ActiveRunPrompt` の変更通知と既存 JLine 出力が prompt redraw を担当する。`ProjectShellActivity` は Event API を使って表示と切り替え時の復元を行う。Agent の cancellation は `CommandCancellationService`、各外部呼び出しの timeout は既存クライアント設定が担当する。プロジェクト別の結果保存には、既存の `ProjectRunStateStore` を拡張できる。別の executor / registry / result store は必要ないと判断した。

## 採用設計

- `ActiveExecution` は実行ID、ProjectId、ConversationId、root、type、依頼概要、開始日時を持つ immutable record。type は `AGENT` / `SUMMARIZE` / `IMAGE`。
- `ConversationInputRouter` の同じ map に Agent と background command を登録する。`activeExecutions()` は全種類、`activeRuns()` は従来どおり Agent のみを返す。
- 既存 executor を共用する。Shell 上では引数を immutable request に確定し、worker へ渡す。worker は mutable な picocli command を参照しない。
- command 用 `ExecutionScope` は AgentRunScope と独立する。ProjectService / ConversationIds / EventFactory / 画像保存先は開始時 ownership を参照する。
- worker の `finally` で active 登録を除外する。正常終了、例外、cancel、timeout、executor 拒否に対応する。
- 結果保存はプロジェクト・種類ごとの lock、固有の一時ファイルと atomic replace を使う。別プロジェクトの処理全体を直列化しない。遅れて保存された古い完了結果は最新結果を上書きしない。
- active state はメモリ内のみ。再起動時に EventStore や保存済み結果から実行中状態を復元しない。

## Summarize

`/summarize <URL>` は検証後に登録し、直ちに Shell に戻る。worker が要約し、開始元の会話へ既存どおり追記する。成功結果は `projects/<ProjectId>/state/latest-summarize.json` に保存する。実行ID、project/conversation ID、開始・完了日時、URL、結果本文を保持する。失敗・中断では以前の成功結果を置き換えない。

`/summarize` 単体は foreground read-only。選択中プロジェクトの現在実行中の要約と、最後に成功した要約を表示する。完了結果がなくても正常終了する。新しい要約実行や履歴追記は行わない。要約完了時には音声通知せず、単体コマンドで保存済み結果を表示したときだけ、その本文を既存executorで非同期に読み上げる。完了結果がない場合は読み上げない。

## Image

`/image "プロンプト"` と従来の `/image generate [options] プロンプト` を受け付ける。オプション指定は既存の generate 構文を使う。`/image` 単体の usage と終了コード2を維持した。

画像生成も登録後すぐ戻る。相対 `--output` は開始元 project root を基準とする。既定の保存ディレクトリは維持し、background の既定ファイル名には実行IDを加えて同時生成時の衝突を防ぐ。完了メタデータと保存先は同じ ProjectRunStateStore の `latest-image.json` に保存する。会話履歴への画像結果の追記は追加していない。

## AgentRun との共存

1 Project 最大1 AgentRun と mailbox の制御を維持した。同じプロジェクトで Agent、要約、画像生成を並列に実行できる。要約・画像だけが実行中なら、通常入力は新しい AgentRun を開始する。Agent が実行中の場合のみ UserIntervention として扱う。`/cancel` の対象は従来どおり選択中プロジェクトの AgentRun。

## Runs / Prompt / Notification

`/runs` は全プロジェクトの PROJECT / TYPE / STATUS / ELAPSED / REQUEST を表示する。プロンプト上の `[プロジェクト名] [N running]` は全種類の合計を表示する。開始・終了時は既存の変更 listener と redraw を使い、入力バッファを変更しない。

Event API に `execution.started/completed/failed/cancelled` と汎用 payload を追加した。payload は実行ID、種類、状態、依頼、出力を保持し、envelope は project/conversation ID と timestamp を保持する。timeout は failed event の `TIMED_OUT` 状態として通知する。別プロジェクト選択中でも `[summarize.completed] A (...)` など、開始元名付きで表示する。通常画面のエラーは短いメッセージ、詳細は既存 logger の debug 出力を使う。background からの直接 System.out/err 出力を抑制する。

## テスト

小さい単位で RED → GREEN を実施した。registry API、command adapter、Shell表示、画像保存先、画像の短縮構文の順で失敗を確認してから実装した。concurrency は controllable executor と CountDownLatch を使い、Thread.sleep は追加していない。

主な追加・拡張テスト:

- `BackgroundExecutionRegistryTest`: Agentとの並存、interventionの独立性、成功・失敗・cancel・timeout・拒否時の除去、実executorでの並列性。
- `BackgroundCommandsTest`: Shellの非待機、引数snapshot、A→B切り替え後のownership、project別最新結果、空結果、実行中と過去結果の併記、失敗時の成功結果維持。
- `ProjectRunStateStoreTest`: 並列保存と再読込、完了順序の逆転、project分離。
- `CurrentConversationHistoryAppenderTest` / `ImageOutputPathResolverTest`: 開始元conversationと相対保存先、画像名の一意性。
- `ActiveRunDisplayTest` / `ActiveRunPromptTest` / `ProjectShellActivityTest`: 全種類の集計、複数project、入力中文字列とcursorの維持、別projectでの成功・失敗通知。
- `ImageCommandTest` / `ConcurrentRunCancellationTest`: 既存構文と短縮構文、bare usage、既存cancel状態との分離。

実行コマンド（Windows）:

```powershell
.\mvnw.cmd -o '-Dmaven.repo.local=C:\Users\mikoto\.m2\repository' '-Drei.data-dir=F:\project\rei\target\test-rei-data' '-Dtest=ImageCommandTest,RootCommandImageTest,ActiveRunPromptTest,Background*Test,ImageCommandCancellationTest,ProjectCancellationTest,ConcurrentRunCancellationTest,ReiApplicationTests' test
.\mvnw.cmd -o '-Dmaven.repo.local=C:\Users\mikoto\.m2\repository' '-Drei.data-dir=F:\project\rei\target\test-rei-data' test
```

関連テスト31件成功。追加の保存・cancel検証を含め、全体テストは1,468件成功（failure/error/skip各0）。結果ログは `target/background-compatibility.log` と `target/background-full.log`。外部の実LLM・画像生成APIを使う課金を伴う手動試験は行っていない。

## 主要変更ファイル

| ファイル | 役割 |
| --- | --- |
| `core/execution/*` | execution record、command scope、要約・画像の実行調整 |
| `core/chat/ConversationInputRouter.java` | 既存registry/executorの一般化 |
| `core/project/ProjectRunStateStore.java` | project/type別成功結果の永続化 |
| `core/project/ProjectService.java`, `llm/ConversationIds.java` | 開始元scopeの解決 |
| `summarize/command/SummarizeCommand.java`, `image/command/*` | Shell command adapter |
| `image/ImageOutputPathResolver.java` | 所属projectの相対パスと一意ファイル名 |
| `event/AgentEvent*`, `event/BackgroundExecutionPayload.java` | 汎用lifecycle event |
| `ui/shell/ActiveRunDisplay.java`, `RunsCommand.java` | 実行中の一覧・件数 |
| `ui/shell/ProjectShellActivity.java`, `ShellAgentEventRenderer.java`, `AgentConsoleSession.java` | 通知と出力 |
| `ReiApplication.java` | Shellの待機回避と出力接続 |
| `core/service/CommandCancellationService.java`, `topic/DefaultAgentActivityTracker.java` | cancelの独立性とactivity判定 |

## コミットと残課題

コミット名: `feat: run summarize and image commands in background`。hash は完了報告に記載する。

background command をID指定でキャンセルするUI、画像単体での直前結果表示、再起動後の処理再開、全完了結果を列挙するcommandは今回追加していない。timeout時間は既存の外部クライアント設定を使用する。Springに接続しない単体commandの旧同期経路は互換性のため維持するが、通常のRei Shellではbackground backendを注入する。
