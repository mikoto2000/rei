# Shell Session commands

## 操作

- `/session` と `/session show`: 現在選択中の metadata を表示。未選択なら `No active session.`。
- `/session list`: 現在の Project の Session を更新日時降順で表示。`*` が現在選択中。共通 QueryService のページを順に取得する。
- `/session new [title]`: 空 Session を即時登録して選択。省略時タイトルは `New session`。空白を含むタイトルは引用符で囲む。
- `/session switch <sessionId>`: 同じ Project の既知 Session を選択。`resume` は既存利用者向けの互換エイリアス。

未知 ID・異なる Project・引数不足・不明な subcommand はエラー表示し、選択を変更しない。保存失敗も旧選択を保持する。管理コマンドは通常の user message として送信しない。

## 調査した既存境界と変更

| 対象 | 既存設計と利用方法 |
| --- | --- |
| Domain | SessionMetadata: sessionId, projectId, title, createdAt, updatedAt |
| Repository | SessionRepository / FileSessionRepository。Shell/Web 共通の Session 台帳 |
| Application | SessionLifecycle が create/validate/touch、SessionQueryService が一覧・詳細を担当 |
| Shell selection | 既存 ShellConversationService → ProjectClient.sessionId。二重管理は追加しない |
| ID | Session ID = conversationId = `project:<project UUID>:chat:<UUID>` |
| 起動 | ProjectClient を作るだけで会話は未選択。最初の通常送信で共通台帳へ登録する既存動作を維持。起動オプションによる Project 選択も維持 |
| 新規作成 | SessionLifecycle.create を追加。Run を開始せず保存してから current selection を更新 |
| Parser/dispatcher | UserInputParser → UserInputService → Picocli。既存 SessionCommand 群を拡張 |
| 履歴 | ChatExecutionService が捕捉した conversationId を使用。ConversationLogStore / ConversationTurnStore は永続化、ContextHistoryAdvisor が次のリクエストを構成 |
| Project | Session 作成時に固定。switch 時は SessionLifecycle.validate で一致を検証。Project 自体を変えない |
| Session state | reiConversation scope が ConversationIds.currentChat() をキーに解決。WorkingSet と他の会話スコープ Bean は選択変更だけで切り替わる。global/project scope は保持 |
| Events | AgentRunContext の ownership は不変。旧 Run を新 Session に移さない。Shell の描画状態を切替時に破棄し、別 Session の遅延イベントを除外 |
| Projection | DefaultAgentUiProjection は Shell の current-state 保持には使用されていない。Shell の ProjectShellActivity / ShellAgentEventRenderer を修正。Project の保存済み RUNNING を Session の current run として復元しない |

実行中の Run は中止しない。`/runs` とプロンプトの running 件数は従来どおり全体の実行状況を表示する。Background execution の明示的な完了通知も維持する。

## TDD と検証

1. 新規 Session が即座に台帳へ登録されるテストを変更し、1 件のままで失敗する Red を確認。共通 create と選択処理を追加して Green。
2. 表示・一覧・new・switch・不正引数のテストを追加。未実装コンストラクタの compile Red 後、既存 Picocli の構造で実装し Green。
3. 同じ Project の旧 Session から遅れて到着したイベントが漏れる Red を確認。Shell の描画境界を修正して Green。
4. 実 ChatExecutionService / ChatClient と固定応答モデルで、次回 request の履歴、新規作成時の非混入、Working Set、永続 Turn を読み直した再開を検証。
5. 圧縮の有効・無効にかかわらず保存済み Session の履歴を読み込むことを provider で検証。

過去 Shell 履歴の migration/backfill、Session の削除・rename・archive・clone・merge・検索、Native UI の変更、Session/Project の再設計は行わない。

全体回帰テストで既存 ToolsTest の Windows ファイルロック競合も再現したため、テストの同期をファイル存在待ちから書き込み後の ready 出力待ちへ変更した。プロセス実行の製品コードは変更していない。

## 最終検証結果

2026-09-26、Windows / JDK 25。Maven test は **2,471 件、Failures 0、Errors 0、Skipped 0、BUILD SUCCESS**。新規 7 ケースと既存テストの更新を含む。既存 `/history`・`/project`・`/cancel`・通常 chat の回帰も全体実行で確認した。

実行時は JAVA_HOME を C:\Java\jdk-25、REI_DATA_DIR をリポジトリ内 target/session-test-runtime に設定し、既存の .m2/repository キャッシュでオフライン Maven を使用した。sqlite-vec のテスト用拡張取得にはネットワークアクセスを許可した。LLM は fake/mock による検証で、実モデルや Native 実端末での手動試験ではない。
