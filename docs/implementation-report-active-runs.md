# プロジェクト横断 ActiveRun 実装報告

## 1. 調査結果

1. Phase 1 の開始・管理は `ChatCommand` → `ConversationInputRouter` → `AgentRunConfiguration` → `ChatExecutionService`。既存 Router に実行中の mailbox 管理がある。
2. `AgentRunContext` は runId、conversationId、projectRoot、projectId を保持し、`AgentRunScope` で実行境界に固定する。
3. RunId は Router が UUID として生成する。
4. ProjectId は `ProjectRegistry` の UUID。プロジェクト別 conversationId から開始時に取得できる。
5. 既存 Router の map が同一プロジェクトの実行判定元だった。
6. 実行中の通常入力は既存 `UserInterventionQueue` へ入り、LLM／Tool 境界で適用される。
7. `ChatExecutionService` が正常終了・失敗・キャンセル・タイムアウトを処理する。例外が内側の try より前に発生する経路にも cleanup が必要だった。
8. 既存イベントは `agent.run.started/completed/failed`。キャンセル・タイムアウトは既存の失敗イベントとエラー情報で表現される。
9. Shell prompt は `ReiApplication.buildPrompt()` が時刻とモデル名から生成していた。
10. 通知・ストリーミング出力は既存 Shell renderer と JLine の表示経路を使う。入力中の件数だけを更新するため、JLine のロック内で動く Widget を使用できる。
11. `/project cd` は ProjectService で選択先を変更し、`ProjectShellActivity.restore()` が会話・Working Set・最近の活動を表示する。
12. slash command は picocli の `RootCommand` 配下に登録する。
13. Agent executor は単一スレッドで、異なるプロジェクトも実際には直列だった。キャンセル状態も共有されていたため、並列化には両方の変更が必要だった。

## 2. 採用設計

新しい Registry は重複作成せず、プロセス全体で一つの `ConversationInputRouter` を拡張した。mailbox と ActiveRun view を同じ Slot に保持し、この map を runtime state の正本とする。EventStore は読み込まない。再起動直後の一覧は空になる。

`ActiveRun` は immutable record で RunId、ProjectId、ConversationId、root、最初の依頼の短縮表示、開始時刻、RUNNING を保持する。終了した Run は削除する。依頼は制御文字・改行を空白にまとめ、最大80コードポイントへ短縮する。追加の LLM 呼び出しはない。

ProjectId を map の identity に使い、UI でだけ ProjectRegistry から名前を解決する。同名は ID の先頭8文字を併記する。旧形式の conversationId に限り正規化した root を互換キーにする。Shell の選択先から実行中 Run の所属を再推測しない。

ConcurrentHashMap のプロジェクト単位の compute と immutable view を使う。異なるプロジェクトは virtual thread で並列実行し、同じプロジェクトでは常に一つの runner にする。表示リスナーの失敗が registry の削除を妨げないように分離した。

## 3. Shell UI

- `/runs`: 全プロジェクトの PROJECT / STATUS / ELAPSED / REQUEST を表示する。実行がなければ `No active runs.`。
- 通常 prompt: 従来の時刻・モデル名に `[current-project] [N running]` を追加する。
- 入力中の開始・終了: JLine Widget 内で prompt を設定し再描画する。入力バッファ・カーソル位置は操作しない。readLine 開始との競合は CALLBACK_INIT で最新値を取得して解消する。
- `/project cd`: `Active runs: N (/runs for details)` を一行追加する。
- 別プロジェクトの終了: `[agent.run.completed] project-name` または `[agent.run.failed] project-name` を既存の出力経路へ通知する。

## 4. Run lifecycle

Router.submit の atomic compute で登録し、その後 executor に渡す。runner の finally で対象 Slot だけを削除する。正常・失敗・キャンセル・タイムアウト・予期しない例外に同じ cleanup を適用し、executor が投入を拒否した場合も削除する。イベント配送の成否から実行状態を判定しない。

`ChatExecutionService` の外側の finally で、その Run のキャンセル状態と activity を解放する。`CommandCancellationService` の状態は RunId ごとに分離し、`/cancel` は現在プロジェクトだけを対象とする。ActivityTracker は Router の実行一覧も確認し、一方が終了しても他方が動作中なら busy を維持する。

完了後の音声読み上げは別タスクへ渡す。読み上げ待ちを AgentRun として数えず、出力経路のための AgentRunScope は引き継ぐ。

## 5. UserIntervention 連携

既存 Run の mailbox が開いていれば通常入力をその queue に送る。ActiveRun は増やさない。終了処理で mailbox が閉じた直後の入力は後続 Slot に保持し、先行 runner の finally 後にのみ起動する。この境界でも同じプロジェクトの runner を重複実行しない。

## 6. テスト

RED → GREEN → REFACTOR の小さな単位で registry、キャンセル分離、UI、cleanup、読み上げを追加した。競合テストは CountDownLatch、制御可能な executor、Flux sink を使用し、Thread.sleep に依存しない。

追加テスト:

- `ActiveRunsTest`: 登録・終了・全例外経路・投入拒否・同一プロジェクトの介入・後続開始・A/B同時実行・50件の並行更新。
- `ConcurrentRunCancellationTest`: A/Bのキャンセル状態と購読の独立性。
- `ConcurrentAgentRunsIntegrationTest`: 実際の ChatExecutionService によるA/Bストリーミング、Bのみキャンセル、A完了、タイムアウト・失敗の削除。
- `ActiveRunDisplayTest`: `/runs`、件数2→1→0、プロジェクト切替、同名表示、過去RUNNINGイベント非復元。
- `ActiveRunPromptTest`: 入力中の件数更新、日本語入力とカーソル位置の保持。
- `ActiveRunActivityTest`: 別Runが動作中ならbusyを維持。
- `ActiveRunNarrationTest`: 読み上げ前にactiveから削除し、別タスクにもRunの所属情報を渡す。
- `ProjectShellActivityTest`: 切替時の件数と別プロジェクト完了通知。

関連テスト50件成功。読み上げの所属情報テストは追加でREDを確認後、1件成功。最初の全体テストでは既存コマンドの単体生成とプロジェクト未指定のキャンセル経路に6エラーを検出し、互換処理を修正した。修正後の関連回帰テスト26件成功。

全体検証コマンド（PowerShell）:

```powershell
.\mvnw.cmd -o '-Dmaven.repo.local=C:\Users\mikoto\.m2\repository' '-Drei.data-dir=F:\project\rei\target\test-rei-data' package
```

全体テスト **1,439件、失敗0・エラー0・スキップ0**。最終ログは `target/active-runs-full-package-final.log`。ストリーミング、Tool、履歴、イベント、プロジェクト切替、Shell入力・通知を含む既存テストも通過した。

同じ package 実行の後段では既存JARの `.original` へのリネームが失敗したため、元POMを変更せず、target内の一時POMで別出力先へ実行用JARを再生成した（BUILD SUCCESS）。この再生成のみテストを省略し、検証済みの全690 class、application.yaml、実行用manifestを検証した。通常の `target/rei-0.0.1-SNAPSHOT.jar` へ反映し、SHA-256一致も確認した。ビルドログは `target/active-runs-jar.log`。

## 7. 主要変更ファイル

- `core/chat/ActiveRun.java`, `ConversationInputRouter.java`: runtime一覧と原子的なライフサイクル。
- `core/chat/AgentRunConfiguration.java`, `ChatExecutionService.java`: 並列実行とcleanup。
- `core/service/CommandCancellationService.java`: Run単位キャンセル。
- `topic/DefaultAgentActivityTracker.java`: 全Runを考慮したbusy判定。
- `core/project/ProjectService.java`: UI向け登録プロジェクト参照。
- `ui/shell/ActiveRunDisplay.java`, `ActiveRunPrompt.java`, `RunsCommand.java`: 一覧・件数・再描画。
- `ReiApplication.java`, `ui/shell/RootCommand.java`, `ProjectShellActivity.java`: Shell接続。
- `ui/shell/sound/ChatResponseNarrator.java`: 完了Run単位の読み上げ。
- 上記テスト、README、本報告書。

## 8. コミット

メッセージ: `feat: track active agent runs across projects`。hash は完了報告に記載する。

## 9. 残課題・実装範囲

`/runs --all`、`--recent`、`/runs cancel <id>`、過去Runの永続管理、GUIは追加しない。キャンセルイベントの新設もせず既存の表現を維持する。実端末での手動操作確認は未実施で、JLineの再描画・入力保持は自動テストで検証した。
