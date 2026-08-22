# Agent Event API v1

## 目的

Agent Core で外部から観測する価値のある状態変化を、UI や LLM provider の型から独立した append-only event として公開する。購読者として logger、debugger、将来の TUI / GUI / SSE adapter を想定する。

## Envelope と payload

`AgentEvent` は `id`, `sequence`, `timestamp`, `type`, `version`, `sessionId`, `turnId`, `runId`, `correlationId`, `parentEventId`, `payload` を持つ。schema version は v1 では `1`。payload は `AgentEventPayload` の sealed hierarchy に属する event 種別ごとの record であり、汎用 `Map<String, Object>` は使用しない。

`sequence` は timestamp ではなく Event Bus 内の正規順序を表す。publish 時に一意な単調増加値が割り当てられ、`lastSequence()` で最新値を取得できる。`correlationId` は started/completed/failed の対応付けに使い、Tool Event では tool call ID を用いる。

## Event Type と統合状況

| 種別 | v1 の状態 |
| --- | --- |
| `agent.run.started`, `agent.run.completed`, `agent.run.failed` | `ChatCommand` の run 境界へ統合済み |
| `message.started`, `message.delta`, `message.completed` | `ChatCommand` の正規化済み response stream へ統合済み |
| `tool.started`, `tool.completed`, `tool.failed` | MCP Tool と Method Tool の `ToolCallback.call` 境界へ統合済み |
| `skill.selection.started`, `skill.selection.completed`, `skill.selection.failed` | `AgentSkillAdvisor` の選定境界へ統合済み |
| `task.created`, `task.started`, `task.completed`, `task.failed` | 型と factory のみ |
| `working_set.item.added`, `working_set.item.removed` | 型と factory のみ |
| `context.snapshot.updated` | 型と factory のみ |
| `file.created`, `file.modified`, `file.deleted` | 型と factory のみ |

Task / Working Set / Context / File は既存機能がそれぞれ独立した状態モデルを持つ一方、共通 lifecycle 境界や event context の受け渡しがまだない。v1 のために架空の統合状態を追加したり、各 Tool メソッドへ発行処理を埋め込んだりすることは避け、型定義に留めた。

## Event Bus

`InMemoryAgentEventBus` は複数 listener、unsubscribe、atomic sequence、`lastSequence()` を提供する。listener の例外は後続 listener と Agent Core を停止させず、SLF4J warning として観測できる。Event Bus はプロセス内だけで完結し、永続化や配送保証は行わない。

```java
AgentEventBus.Subscription subscription = eventBus.subscribe(event -> logger.info("{}", event));
subscription.unsubscribe();
```

## Message stream の境界

Provider 固有の streaming response や SSE を UI へ渡さない。`ChatCommand` が assistant text を `message.started`、順序付きの `message.delta`、`message.completed` に正規化する。このため購読側は OpenAI 等の provider API に依存しない。

## Tool interception

MCP の `SyncMcpToolCallbackProvider` は `ToolEventCallbackProvider` で包む。`defaultTools(...)` に渡していた Java object は `MethodToolCallbackProvider` で公開 API を使って `MethodToolCallback` に変換し、同じ provider decorator で包んでから `defaultToolCallbacks(...)` に登録する。したがって個々の `@Tool` method に event code はない。

`ToolEventCallbackDecorator` は呼出し前後で started/completed/failed を発行する。arguments/result は短い summary のみとし、巨大な file content や result 全文、stack trace を複製しない。

## Skill selection

`AgentSkillAdvisor` は選定処理の前後で `skill.selection.started/completed/failed` を発行する。各選定に UUID の `selectionId` を割り当て、payload と `correlationId` で lifecycle を関連付ける。completed payload は明示選択名、暗黙選択名、警告を別々の immutable list として保持する。従来の直接標準出力は行わず、表示は Event consumer に委ねる。

Spring AI 2.0.0-M3 の `DefaultToolCallingManager` は LLM の `AssistantMessage.ToolCall.id()` を `ToolContext` に追加せず、prompt の static tool context だけを渡す。そのため `toolCallId` が context に明示されている場合はそれを採用し、ない場合は一回の decorator 呼出しにつき UUID を生成して started と completed/failed で共有する。LLM ID の完全な伝播には Spring AI 側の公開 extension point 追加、または ToolCallingManager の差し替えが必要になる。

## Snapshot

大規模な状態集約は v1 の対象外とした。現在は途中参加する購読者が境界を把握するための `AgentEventBus.lastSequence()` を提供する。run、tasks、working set、active tools、context を集約する完全な `AgentSnapshot` は、それらの lifecycle が一つの状態 owner に集約された段階で追加する。

## v1 の対象外と拡張

Event Store、完全な Event Sourcing、永続化、replay、broker、HTTP、SSE、WebSocket、TUI、GUI、分散配送は対象外。将来は Agent Core を変更せず、`AgentEventListener` を実装する SSE serializer、UI state projector、persistent logger を Event Bus に購読させる。
