# Agent TUI v1.1

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

既存shellで `/tui` を実行するか、起動時に `--tui` を指定する。

```powershell
java -jar target/rei-0.0.1-SNAPSHOT.jar --tui
```

optionなしは従来どおりShellを起動する。`--tui` はShellへ文字列を疑似入力せず、startup mode判定から共通の `AgentTuiLauncher` を直接起動する。Shellの `/tui` も同じlauncherを既存JLine terminal付きで呼ぶ。

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
* Enter: 空でない入力を共通parserへsubmit
* Ctrl+C: 実行中なら既存cancellation APIでAgentだけをcancel、待機中ならTUIを終了

queueや並列Runは作らない。

## Slash command

ShellとTUIは `UserInputParser` で空入力・通常chat・slash commandを同じ規則で分類し、引用符を含む引数も同じtokenizerで処理する。TUIのslash commandは既存のpicocli `RootCommand` を実行し、stdout/stderrとpicocli writerを回収してAssistant領域へ表示する。command状態はAgentUiProjectionへ混ぜずTUIローカル状態に保持する。

`/help`、`/version` とRootCommand配下の `search`、`models`、`model`、`project`、`config`、`schedule`、`embed`、`task`、`feed`、`briefing`、`reminder`、`bsky`、`interest`、`memory`、`skill`、`image` を利用できる。`/sh` は外部terminal lifecycle、`/paste` はShellの複数行readerに依存するためTUIでは利用不可。`/tui` は `Already in TUI mode.` と表示して再帰起動を防ぐ。

RUNNING中は `/help` と `/version` および `/exit` を許可し、通常chatと状態変更を伴うcommandは拒否する。`/exit` はTUIを閉じる。Shellから入った場合は呼び出し元のShell loopへ戻り、`--tui` 起動ではmainへ戻ってApplicationを正常終了する。

## 実行、redraw、shutdown

Agent実行はdaemon single-thread executorへ委譲し、TUI event loopをblockしない。100msのTamboUI TickでProjection snapshotを再取得するため、keyboard操作がなくてもstreamingを最大10fpsで再描画する。busy loopは使用しない。

TUI開始時にProjectionをEvent Busへsubscribeし、終了時にunsubscribeする。`TuiRunner`のtry-with-resourcesがraw mode、alternate screen、cursorを復元する。finallyでAgent cancellation、executor shutdown、subscription解除を実行する。

既存ChatCommandとToolがstdout/stderrへ出すCLI向け表示は、TUI実行中だけnull streamへ退避し、SLF4Jの重要ログは既存rolling file `./.rei/rei.log`へ継続出力する。TamboUI自身のerror outputは退避前のstderrを保持する。

## Test とmanual smoke

Terminal不要のtestで、Unicode入力編集、共通入力分類、startup mode、slash routing、RUNNING中policy、複数行command出力、未知command、launcher共有、4 Run状態、streaming、日本語、長文、3 Tool状態、Event Busからrender modelまでを検証する。

実Terminal smoke手順:

1. `./mvnw.cmd spring-boot:run` をPTY対応terminalで起動
2. `/tui` を入力しalternate screenを確認
3. 日本語を入力し、cursor編集を確認
4. `/help` を入力し、複数行のcommand一覧を確認
5. `/tui` が再帰起動せずメッセージになることを確認
6. `/exit` でShellへ戻ることを確認
7. `java -jar ... --tui` でShell bannerなしにTUIへ入り、`/exit` でApplicationが終了することを確認
8. 通常入力でAgentを実行し、RUNNING、streaming、Tool、完了状態を確認

2026-08-23にPTY上でShell経由と`--tui`直接起動がそれぞれTUI初期化へ進むことを確認した。Codex PTYはTamboUIの端末能力問い合わせと画面差分を完全にはエミュレートしないため、`/help`描画と`/exit`復帰は自動testで検証した。外部LLMを使うstreaming/Toolのend-to-end smokeは実施していない。

## v1の対象外・制約

Task/Working Set/Context/File pane、履歴、replay、mouse、markdown完全描画、syntax highlight、Tool詳細、複数Run、queue、session永続化は対象外。user messageはcanonical historyとして表示せず入力欄だけに存在する。手動scrollはなく最新表示を優先する。Agentがcancellationに即応しない処理中でもworkerはdaemonであり、終了時は2秒だけ待つ。

v2ではstate-change driven redraw、manual scroll、conversation history、Task/Context pane、logging appenderのTUI統合、TamboUI pilot testを追加できる。
