# Shell Event Rendering v1

## 目的と構造

既存Shellの履歴、補完、line editingを維持しながら、Agent Eventをappend-onlyに観測する。

```text
Agent Core -> Agent Event Bus -> ShellAgentEventRenderer -> JLineShellEventOutput
                           \-> AgentUiProjection -> future UI consumer
```

ShellはProjectionをpollingせずEvent Busを直接購読し、受信順（Busのsequence順）に表示する。対象は `agent.run.started/completed/failed`、`message.started/delta/completed`、`tool.started/completed/failed`、`skill.selection.started/completed/failed`。Task、Working Set、Context、File eventはv1対象外。

## 表示

```text
[agent] running
[skill] selecting
[skill] selected: filesystem (explicit), browser (implicit)
assistant streaming text
  → toolName
  ✓ toolName (84 ms)
  ✗ toolName: error summary
[agent] completed (1.2 s, 456 tokens, TTFT 123.5 ms, output 78.9 tok/s, end-to-end 65.4 tok/s)
```

`message.delta` はprefixを付けず `print` とflushを行い、`message.completed` で行を閉じる。Tool eventがassistant行へ割り込む場合は先に改行し、Toolを独立行で表示した後、空行を挟んでassistant streamingを再開する。Rendererは表示上の「assistant行が開いているか」だけを持ち、新しいRunでリセットする。全event処理は`synchronized`で文字単位の競合を防ぐ。

Run完了行にはコマンド全体の経過時間、completion token数、最後の回答ストリームの TTFT、output tok/s、end-to-end tok/s を表示する。TTFT はリクエスト開始から最初の可視回答チャンクまで、output tok/s は `(completion tokens - 1) / (最後の回答チャンク時刻 - 最初の回答チャンク時刻)`、end-to-end tok/s は `completion tokens / (ストリーム完了時刻 - リクエスト開始時刻)` とする。出力上限による再計画やサブゴールで複数回LLMを呼び出した場合、completion token数は各呼び出し分を合算し、時間系の指標は最後の回答ストリームの値を表示する。usage がない指標、または回答が単一チャンクで output tok/s を測定できない場合は unavailable と表示する。

Tool開始行は `→ toolName argumentsSummary` として、redact・120文字制限済みの引数要約をTool名の横に表示する。改行と端末制御文字は空白へ変換し、空の引数は表示しない。

Tool failure eventにはdurationがないため算出せず、event内のtool名とerror summaryだけを表示する。stack traceと詳細は既存file loggingへ任せる。

## JLineと入力行保護

通常のAgent command実行中は `LineReader.isReading()` がfalseなので、JLine terminal writerへdeltaを直接書きflushする。入力編集中にbackground eventが届いた場合はdeltaを行境界までbufferし、`LineReader.printAbove()` で出力する。これにより編集中bufferとcursorはJLineが再描画する。独自ANSI cursor制御は行わない。

## 二重表示の防止

`ChatCommand`とTool群にはEvent API以前の直接stdout出力が残る。Shellのchat command実行中だけ `System.out/err` をnull streamへ退避し、表示経路をEvent Busへ一本化する。slash commandはこのpolicy対象外で従来出力を維持する。TUIも従来どおり自身のstdout抑制とProjection描画を使う。

## Subscription lifecycleとTUI排他

Shell開始後、LineReader構築時に `ShellEventSession` がsubscribeする。Shell終了のfinallyでunsubscribeする。

## Testとmanual smoke

自動testではRun lifecycle、失敗summary、日本語delta、prefix非重複、Tool 3状態、duration有無、message/tool interleave、複数Run reset、thread-safe output boundary、JLine `printAbove`、pause/resume/unsubscribe、legacy chat stdout抑制、slash stdout維持を確認する。

実Terminal smoke手順:

1. Shellを起動し、履歴とTab補完を確認する。
2. 通常chatを実行し、`[agent] running`、assistant streaming、Tool行、完了行を確認する。
3. Tool interleave後もassistantと次promptが正しい行にあることを確認する。
4. background event中に文字を編集中にして、event表示後に入力とcursorが復元されることを確認する。
5. `/help` の出力を確認する。

外部LLMが利用できない環境では、event列は `ShellAgentEventRendererTest` で再現する。実LLM/Toolのend-to-end確認は接続可能なterminalで上記手順を実施する。

2026-08-23のCodex PTYでは最新クラスによるShell起動とprompt表示を確認した。ただし同PTYはJLineへのEnterを通常の対話terminalとして配送しないため、chat、`/help` の操作完了は観測できなかった。event表示、interleave、入力行復元は上記自動testで確認し、完全なend-to-endはWindows Terminal等で実施する。

## v1対象外

Task/Working Set/Context/File表示、Tool結果詳細、replay/persistence、spinner、progress bar、Markdown、syntax highlight、theme、filter、verbosity設定、log viewerは対象外。
