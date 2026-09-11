# SubAgent

SubAgent は、目的・独立コンテキスト・制限された Tool 権限を持つ一時的な実行環境です。
親「れい」は `delegateTask` で処理を委譲し、型付きの最終結果だけを受け取ります。
例えば「researcher に調べさせて」「reviewer にこのコードをレビューさせて」と依頼できます。
実際に委譲するかどうかは親 LLM が Tool を選択して決定します。

## 配置と追加

既定は `<rei-data-dir>/config/subagents/`。Windows の既定データディレクトリは
`%LOCALAPPDATA%/Rei`、`REI_DATA_DIR` で変更できます。
プロジェクト切替によって定義や権限が変わらない、既存のグローバル設定と同じ方式です。
外部 `config.yaml` で `rei.subagents.directory` に絶対パスを指定することもできます。
相対パスは起動時の作業ディレクトリを基準に一度解決します。

リポジトリの [researcher](../config/subagents/researcher.yaml) と
[reviewer](../config/subagents/reviewer.yaml) を上記ディレクトリにコピーして利用してください。
サンプルを自動インストールしてユーザー設定を書き換えることはありません。
開発時は `rei.subagents.directory=config/subagents` でも利用できます。

```text
/subagent init java-expert
# 表示された YAML を編集
/subagent validate <file>
/subagent reload
/subagent list
/subagent show java-expert
```

`init` は ID を検証し、既存ファイルを上書きしません。生成後の編集と reload は明示的に行います。
`/subagent` 単体は `list` と同じです。`show` は定義元パス、要求／有効 Tool、モデル、制限値を表示します。
通常表示では systemPrompt 本文を表示しません。validate はファイルを検査するだけで登録しません。
既存 Picocli の Shell コマンドに統合しており、新しい CLI フレームワークはありません。

## YAML schema

```yaml
id: reviewer
name: Reviewer
description: コードと設計を独立してレビューする
systemPrompt: |
  渡されたタスクを独立して検証し、根拠と結論を報告してください。
tools: [readMultiFile, grepMultiQuery]
maxSteps: 10
timeout: 120s
# model: my-model
```

| 項目 | 意味 |
| --- | --- |
| id | 必須。`[a-z][a-z0-9-]{0,63}`、ディレクトリ内で一意 |
| name / description | 必須の空でない文字列。親に公開する選択用情報 |
| systemPrompt | 必須。子専用の指示。親には全文を公開しない |
| tools | Tool 名のリスト。省略時は空。権限ではなく利用要求 |
| model | 任意。未指定なら現在のチャットモデル設定 |
| maxSteps | 必須の正整数。初回を含む論理 LLM 呼び出し回数の上限。provider 内部 retry/fallback は同一 step |
| timeout | 必須の正の時間。`ms` / `s` / `m` / `h`、例 `120s` |

モデル指定は既存チャット provider 内のモデル ID です。現在のモデル、chat feature の設定モデル、
または管理設定 `rei.subagents.models` に列挙したモデル ID をローカルで解決します。
例: `rei.subagents.models: [my-model]`。YAML から接続先・API key・Java クラスは指定できません。
validation は provider への通信を行わないため、実際のモデル稼働状態は実行時に判明します。

SafeConstructor を利用し、任意クラス生成、重複キー、コレクション alias、不明項目、
不正な型・時間・Tool・モデル参照を拒否します。ファイルは 256 KiB、ネストは 10 段までです。
設定エラーはファイルと設定項目・理由を通知します。起動時に不正設定があっても本体を停止せず、
空の Registry で起動します。reload は全件検査に成功した場合のみ immutable snapshot を差し替えます。
失敗時は現在の正常な一覧を維持します。一覧は ID 順。実行中の子は開始時に取得した定義を使い続けます。

## Tool Policy

`SubAgentToolPolicy` の一箇所に許可を集約しています。

- `readMultiFile`, `grepMultiQuery`, `searchAndRead`, `readPdfFile`
- `webSearch`, `webSearchAndRead`, `searchKnowledge`

既存 taxonomy は Tool 説明文の preferred/workflow/primitive 区分で、強制可能な分類型はありません。
Phase 1 では名前と実在する組み込み callback の両方を照合します。新しい Tool が増えても自動許可しません。
Shell / Process、ファイル書込、ドメイン更新、Utility / Demo、MCP、`delegateTask` は許可しません。
ファイル検索内部の固定 `git ls-files` は既存実装を利用しますが、任意コマンド引数の Tool は公開しません。

`effectiveTools = requestedTools ∩ allowedTools ∩ availableCallbacks` です。
不明・禁止 Tool は黙って除去せず設定エラーにします。実行時も再検査し、
LLM が未公開の Tool を要求した場合はバッチ全体を実行前に拒否します。
Tool 引数のファイルアクセス範囲やネットワークアクセス制限は既存 Tool と同じです。
これは OS サンドボックスを追加する機能ではありません。

## 実行と履歴

`delegateTask(agent, task, context?)` は以下の情報を返します。
`agentId`, `subAgentRunId`, `status`, `output`, `startedAt`, `completedAt`。
状態は `COMPLETED`, `FAILED`, `UNKNOWN_AGENT`, `MAX_STEPS_EXCEEDED`, `TIMEOUT`, `CANCELLED` です。

子の入力は YAML の systemPrompt と task、明示的 context のみです。
親の ChatMemory、ConversationLog、Skill・Working Set・計画・要約 Advisor を使いません。
子内部の Tool 結果は実行内のメッセージリストだけに保持し、main history へ追加しません。
親が受け取るのは委譲結果です。Working Set は継承せず、ファイル Tool 用の一時 Working Set と
キャッシュを生成して終了後に破棄します。ファイルの基準位置とイベントの projectId は親から固定して引き継ぎます。

上限に達してなお Tool loop が必要なら `MAX_STEPS_EXCEEDED` とし、正常終了にしません。
timeout は最終出力までの全ループに適用し、Reactor subscription を dispose して
実行中の通信ストリームを cancel、Tool ワーカーを interrupt します。
親キャンセルは `CommandCancellationService` の子登録を通じて伝播します。
`SubAgentRunner.cancel(subAgentRunId)` も用意しています。各境界で停止状態を再検査し、次の LLM／Tool を開始しません。
外部ライブラリや同期 I/O が interrupt を無視する場合、その実行中の呼び出しの即時停止までは保証できません。
停止後に結果を採用したり、後続 Tool を起動したりはしません。

## イベントと現在の制約

`subagent.started` / `subagent.completed` / `subagent.failed` を既存 Agent Event Bus へ発行します。
payload に親・子 Run ID、agent ID、最大 120 文字の redact 済みタスク要約、状態、時間、失敗理由を保持します。
Envelope の runId は子、correlationId は親です。Tool イベントは既存の `tool.*` を子の所属で発行します。
Shell は開始・終了に加え、子の LLM リクエスト・応答・失敗、Tool 呼び出し・完了・失敗などのイベントを表示します。
内部イベントには `[subagent:エージェント名/実行ID]` を付け、同じ定義の複数実行も区別します。
開始イベントがない場合は実行 ID のみを表示します。履歴の簡易表示でも子のイベントを確認できます。
子の回答・思考の token stream は親の回答に混ぜず、親の UI 状態も変更しません。

永続化するのは既存の監査イベントで、子専用の会話履歴は保存しません。
複数 Run の ID と状態は独立していますが、自動並列 orchestration はありません。
再帰委譲、Agent 間会話、動的生成、DAG planner、協調プロトコル、GUI、remote agents は Phase 2 以降です。
