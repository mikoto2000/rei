# Agent TUI v1

## 目的と構造

TUI v1 は「入力する、Agent が動く、assistant streaming と Tool 状態を見る、完了後に次を入力する」という最小 Agent console を提供する。

```text
Agent Core -> Agent Event Bus -> AgentUiProjection -> AgentUiState -> TUI
                                                               -> TamboUI
                                                               -> JLine 3 backend
```

TUI は Agent Event を解釈せず、`AgentUiProjection.currentState()` の immutable snapshot のみを描画する。入力、cursor、terminal size等のUI状態だけをTUI内に持つ。TamboUI固有APIは `dev.mikoto2000.rei.ui.tui` に閉じ込めた。

## TamboUI

公式snapshot repositoryから `0.5.0-SNAPSHOT` を利用する。今回解決されたtimestamp版は `0.5.0-20260812.202257-6`。

* `dev.tamboui:tamboui-tui`
* `dev.tamboui:tamboui-jline3-backend`

高レベルの `TuiRunner` を採用し、JLine backendはTamboUIのServiceLoaderで選択される。TamboUIはexperimentalであるため、snapshotの更新でAPIが変わる可能性がある。

## 起動と画面

既存shellで `/tui` を実行する。

```text
Status (3 rows):       rei / IDLE, RUNNING, COMPLETED, FAILED
Assistant (flex):      最新assistant message
Tools (30%):           → RUNNING, ✓ COMPLETED, ✗ FAILED
Input (3 rows):        > input / [RUNNING] input
```

`Constraint.length`, `fill`, `percentage` によるvertical layoutを毎frame計算するためresizeへ追従する。30x10未満では `Terminal too small` へdegradeする。Assistantは末尾の明示的な改行が見えるよう自動scrollし、Toolは表示可能件数に合わせて最新実行を選ぶ。表示幅とcursor位置はTamboUIの`Text.width()`を使い、`String.length()`による独自CJK幅計算はしない。

## 操作

* 通常文字・日本語: cursor位置へ追加
* Left / Right / Home / End: cursor移動
* Backspace / Delete: code point境界で削除
* Enter: 空でなくAgentが実行中でない場合にsubmit
* Ctrl+C: 実行中なら既存cancellation APIを呼び、TUIを終了

RUNNING中の入力は保持するがsubmitしない。queueや並列Runは作らない。

## 実行、redraw、shutdown

Agent実行はdaemon single-thread executorへ委譲し、TUI event loopをblockしない。100msのTamboUI TickでProjection snapshotを再取得するため、keyboard操作がなくてもstreamingを最大10fpsで再描画する。busy loopは使用しない。

TUI開始時にProjectionをEvent Busへsubscribeし、終了時にunsubscribeする。`TuiRunner`のtry-with-resourcesがraw mode、alternate screen、cursorを復元する。finallyでAgent cancellation、executor shutdown、subscription解除を実行する。

既存ChatCommandとToolがstdout/stderrへ出すCLI向け表示は、TUI実行中だけnull streamへ退避し、SLF4Jの重要ログは既存rolling file `./.rei/rei.log`へ継続出力する。TamboUI自身のerror outputは退避前のstderrを保持する。

## Test とmanual smoke

Terminal不要のtestで、Unicode入力編集、空入力、RUNNING中submit拒否、4 Run状態、streaming、日本語、長文、3 Tool状態、順序、最新Tool選択、Event Busからrender modelまでを検証する。

実Terminal smoke手順:

1. `./mvnw.cmd spring-boot:run` をPTY対応terminalで起動
2. `/tui` を入力しalternate screenを確認
3. 日本語を入力し、cursor編集を確認
4. EnterでAgentを実行し、RUNNING、streaming、Tool、完了状態を確認
5. 次の入力を送信
6. Ctrl+Cで終了し、shellのecho、cursor、screenが復元されたことを確認

2026-08-23にPTY上で起動、日本語入力、Ctrl+C終了、Terminal復元を確認した。外部LLMを使うstreaming/Toolのend-to-end smokeは実施していない。これは自動testでEvent Busからrender modelまでを検証しているが、実backendでの完全対話確認は利用環境のLLM接続を有効にして上記手順で行う必要がある。

## v1の対象外・制約

Task/Working Set/Context/File pane、履歴、replay、mouse、markdown完全描画、syntax highlight、Tool詳細、複数Run、queue、session永続化は対象外。user messageはcanonical historyとして表示せず入力欄だけに存在する。手動scrollはなく最新表示を優先する。Agentがcancellationに即応しない処理中でもworkerはdaemonであり、終了時は2秒だけ待つ。

v2ではstate-change driven redraw、manual scroll、conversation history、Task/Context pane、logging appenderのTUI統合、TamboUI pilot testを追加できる。
