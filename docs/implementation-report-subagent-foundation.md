# SubAgent foundation 実装報告

開発ブランチ: `feature/subagent-foundation`。開始時の `main` はクリーンで、既存変更の破棄・stash は行っていません。
使い方と schema は [subagents.md](subagents.md) を参照してください。

## 調査した既存構造と統合判断

| 対象 | 既存実装と今回の扱い |
| --- | --- |
| Agent / Chat の入口 | `UserInputService` → Picocli `RootCommand` / `ChatCommand` → `ConversationInputRouter` → `ChatExecutionService`。親の実行経路は維持 |
| LLM | `LlmModelProvider`, `LlmChatClientProvider`, `AgentEventChatModel`, `FallbackChatModel`。子は同じ provider、options、イベント付きモデルを再利用 |
| Tool 呼出し | Spring AI `MethodToolCallbackProvider`, `ToolCallingManager`。`StagnationChatModel` の明示ループで使用中。dispatch 境界を `ToolLoopSupport` に共通化 |
| Tool taxonomy | 説明文の preferred/workflow/primitive が中心で、権限を表す分類型はない。監査済み組み込み Tool の名前による中央 allowlist を採用 |
| Agent Event / Bus | sealed `AgentEventPayload`, `AgentEventFactory`, `InMemoryAgentEventBus`。既存 envelope と publisher に追加 |
| UI | `AgentUiProjection` / `AgentUiState` は親の可視状態。子の一時 conversation のイベントを混ぜず、Shell は lifecycle と子の LLM・Tool 等のイベントを実行 ID で区別して表示 |
| Working Set | 実際に読書きしたファイルを記録する project scope の永続状態。参照 Tool にも更新副作用があるので、子では一時 Tools / WorkingSet / cache を生成 |
| cancel | `CommandCancellationService` が Run ID ごとに subscription と実行スレッドを管理。親の subscription を上書きしない子 cancel 登録を追加 |
| background | `ConversationInputRouter`, `BackgroundCommands`, `ExecutionScope` は Shell の summarize/image 実行管理。子は Agent Run の子処理であり、別種の永続 background job にしない |
| `/history` | `HistoryShellService` / `ConversationLogStore` を利用。子はこれらへ書き込まない |
| `/summarize` | `WebPageSummarizerService` / `BackgroundCommands`。明示的な履歴追記を担う `CurrentConversationHistoryAppender` は子では使用しない |
| `/image` | `ImageGenerationService` / `BackgroundCommands`。ファイル生成能力を子へ公開しない |
| 設定 / YAML | `ExternalConfigSupport`, `ReiDataDirectory`, Spring Boot application/config YAML。SnakeYAML は既存依存にあり追加依存なし。Skill front matter は独自 parser で、今回の厳密 YAML には流用しない |
| model / provider | 現在のモデルは `ModelHolderService`、feature 選択は `LlmProperties`。子は chat provider を利用し、モデル ID のローカル解決を追加 |
| system prompt | 親は `SystemPromptService` と各 Advisor。子には YAML の prompt のみを渡す |
| conversation | `AgentRunContext` / `AgentRunScope` で project location を固定し、子には `subagent:<UUID>` を付与。memory advisor を通さない |
| テスト | JUnit 5 / AssertJ / Mockito / jqwik、Maven Wrapper、JDK 25。追加の CI formatter/checkstyle/spotbugs 設定は見つからず |

親の `ChatExecutionService` 全体を子へ流用すると、main history、添付展開、Working Set、計画、
介入、stagnation replan を暗黙に引き継いでしまいます。そのため低層の既存 provider と Tool dispatch を再利用し、
application/core の `BoundedToolLoop` に短い一時実行ループを置きました。親の stagnation policy は変更していません。

## 主要クラス

- `SubAgentDefinition`: provider 非依存 immutable record。
- `SubAgentDefinitionLoader`: SafeConstructor と厳密な型・項目検査。
- `SubAgentRegistry`: ID 順 snapshot と全件成功時の atomic reload。
- `SubAgentToolPolicy`: 名前による既知／許可判定と requested ∩ allowed。
- `SubAgentToolCatalog`: 実 callback と子専用ファイル参照状態の生成。
- `SubAgentRunner` / `SubAgentResult`: 隔離、制限、cancel、最終結果、イベント。
- `BoundedToolLoop` / `ToolLoopSupport`: streaming aggregation、明示 Tool loop、既存 dispatch の再利用。
- `SubAgentTools` / `LazyDelegationCallback`: 親専用 `delegateTask`。catalog は callback 定義取得時に Registry から読んで reload を反映。
- `SubAgentCommand`, `SubAgentConfiguration`, `SubAgentProperties`: 既存 Shell と Spring 構成への統合。
- `SubAgentLifecyclePayload`: 親・子 ID と制限付き要約を持つ既存 API の payload。

`delegateTask(agent, task, context?)` の context は任意です。返却は agentId / subAgentRunId /
status / output / startedAt / completedAt。親へ公開する定義は id / name / description のみです。
unknown agent、通常失敗、maxSteps、timeout、cancel は識別可能です。

## 制限と権限

readMultiFile / grepMultiQuery / searchAndRead / readPdfFile / webSearch / webSearchAndRead / searchKnowledge を許可。
要求 ∩ 許可 ∩ 実在 callback を構築し、禁止要求は設定エラー、実行時の未知 Tool は実行前にバッチごと拒否します。
provider の既定 Tool が補完されることも防ぐため、既定 Tool を持つモデルは子実行に使用しません。
Shell・書込・ドメイン更新・MCP・delegateTask は公開しません。

maxSteps は初回を含む論理 LLM 呼出し回数。provider 内部の retry/fallback は同じ step です。
timeout は全ループに適用し、subscription disposal と Tool ワーカー interrupt を行います。
親 cancel 登録、個別 Run ID cancel、各 Tool/LLM 境界での停止確認を併用します。
interrupt に対応しない既存 I/O の即時終了は保証できませんが、その後の結果は採用せず次の呼出しも開始しません。

子の内部メッセージは実行内リストだけに存在し、ChatMemory / ConversationLog を読み書きしません。
Working Set は共有も snapshot 継承もせず、子の読取副作用も一時状態に閉じます。
Project root / projectId のみ開始時に固定します。並列 run 用の ID、cancel 状態、Tool インスタンスは独立しています。

## イベントとコマンド

追加イベントは `subagent.started`, `subagent.completed`, `subagent.failed`。
payload は parentRunId / subAgentRunId / agentId / taskSummary / status / duration / failureReason。
runId は子、correlationId は親。taskSummary は既存 CredentialRedactor を通して 120 文字以内。
Tool lifecycle は既存 `tool.*` を子所属で発行し、独自の重複 Tool イベントはありません。
Tool/LLM 失敗の例外メッセージも redact・長さ制限を行うよう既存イベント境界を補強しました。

コマンド: `/subagent`, `/subagent list`, `/subagent show <id>`, `/subagent reload`,
`/subagent validate <file>`, `/subagent init <id>`。validate/init とも実装済みです。
init は ID を制限し CREATE_NEW で既存ファイル・symlink を上書きしません。

## 依頼の候補から変更した点

| 変更点 | 理由 | 代替実装 |
| --- | --- | --- |
| 既定の配置 | 現在はプロジェクト内ではなく OS 標準のグローバル設定構造 | `<rei-data-dir>/config/subagents`、設定で変更可能。サンプルは repo の `config/subagents` |
| 独立 CLI validate | 現在の main は REPL 起動であり引数による一回実行の dispatcher はない | 既存 Picocli の `/subagent validate` |
| model validation | 起動/reload が外部 `/models` の可用性・遅延に依存しないようにする | 現在/chat feature のモデルと `rei.subagents.models` の管理設定 ID に対するローカル解決。稼働確認は実行時 |
| 起動時の不正設定 | 任意拡張の設定エラーで本体機能を停止しない | 診断して空 Registry。reload は旧 snapshot 維持 |

## TDD と検証

Red → Green → 境界の整理を次の単位で実行しました。

1. Definition / Loader / Policy / Registry: 未実装によるコンパイル失敗後、正常・欠落・型・時間・Tool・model・重複・atomic reload のテストを通過。
2. Runner: 未実装による失敗後、制限・分離・イベント・cancel を実装。Tool ワーカー interrupt と兄弟 Run の個別 cancel を追加検証。
3. 委譲 / コマンド: 未実装による失敗後、型付き結果と動的 catalog、list/show/reload/validate/init を実装。
4. Event: 子 Tool の親 UI 混入と例外内 credential の Red を確認して修正。
5. 設定の null キーとモデル既定 Tool の境界を追加し、失敗を確認して修正。
6. 実ファイル Tool + Spring 統合、サンプル YAML、永続イベント payload 往復を検証。

新規テストは `SubAgentConfigurationTest`, `SubAgentRunnerTest`, `SubAgentToolsTest`,
`SubAgentCommandTest`, `SubAgentEventTest`, `SubAgentModelDefaultsTest`, `SubAgentIntegrationTest`。
既存テストの削除・無効化はありません。
実行コマンドは `./mvnw.cmd -q -Dtest=SubAgent*Test test` などの対象テスト、および標準の `./mvnw.cmd -q test`。
最終の `./mvnw.cmd -q test` は終了コード 0。Surefire XML 集計で 337 スイート、
1,629 テスト、失敗 0、エラー 0、スキップ 0（新規 SubAgent テスト 22 件）です。
差分チェック `git diff --check` も成功しました。commit hash は最終報告に記載します。

## 今回実装しない範囲

永続的な子会話、再帰委譲、自由会話、自動生成、自動並列 orchestration、協調プロトコル、
DAG planner、GUI、remote/MCP agent federation は実装していません。
ライブ LLM/Web provider に対する外部通信試験と GraalVM native image ビルドは実行していません。
