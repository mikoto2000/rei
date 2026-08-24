# Agent UI Projection v1

## 目的と境界

Agent UI Projection は、append-only な [Agent Event API v1](agent-event-api-v1.md) の Event 列を、将来の GUI 等が描画しやすい immutable な現在状態へ変換する consumer である。

```text
Agent Core -> Agent Event Bus -> AgentUiProjection -> AgentUiState -> UI consumer
```

Agent Core は Projection や UI に依存しない。Projection は Event payload の履歴や UI widget を公開せず、UI framework、terminal、HTTP にも依存しない。UI consumer は Event の lifecycle を個別解釈せず `currentState()` を描画する。

## State

`AgentUiState` は次を保持する。

* `run`: `AgentRunView`
* `messages`: 開始順の `MessageView`
* `tools`: 開始順の `ToolExecutionView`
* `lastSequence`: 最後に正常適用した Event の sequence

公開 State と各 View は record であり、collection は `List.copyOf` で defensive copy する。取得済み snapshot は後続 Event によって変化せず、呼出し側からも変更できない。

## 状態遷移

Run は `IDLE -> RUNNING -> COMPLETED | FAILED`。新しい `agent.run.started` は別 Run の Message / Tool が混ざらないよう両履歴をクリアする。Envelope の timestamp を started/completed time、payload の duration/error を表示状態へ写す。

Message は `message.started` で `STREAMING` として追加し、同じ messageId の `message.delta` を一つの text に累積する。`message.completed` は payload の最終 text を authoritative value として `COMPLETED` にする。現在 Event API から観測できるのは assistant message のみだが、Projection 自体は role を固定せず将来の user message も扱える。

Tool は correlationId（ない場合は payload の toolCallId）を識別子とする。`tool.started` で `RUNNING` として開始順に追加し、同じ識別子の `tool.completed` / `tool.failed` を同一 View の `COMPLETED` / `FAILED` へ更新する。同じ toolName でも correlationId が異なれば別実行である。

## Sequence と不完全な列

Event の正規順序は timestamp ではなく sequence。正の sequence が `lastSequence` 以下なら stale/duplicate として無視し、state の巻き戻りを防ぐ。Event Bus を経由する通常 Event は必ず正の sequence を持つ。

started が欠落した message delta/completed または tool completed/failed では、分かる値だけを持つ placeholder を作成する。不正な payload 型等は warning log を残して無視し、Projection の例外で Agent Core を停止させない。

## Thread safety と購読

`DefaultAgentUiProjection` は `apply` と `currentState` を同期化する。現在の Event Bus の同期 delivery に対して単純であり、将来複数 thread から publish されても accumulator と snapshot の整合性を守る。

Projection は `AgentEventListener` を実装しているため直接購読できる。

```java
AgentUiProjection projection = new DefaultAgentUiProjection();
AgentEventBus.Subscription subscription = eventBus.subscribe(projection);
AgentUiState state = projection.currentState();
```

UI は Event 到着を再描画契機として利用できる。v1 では UI framework 固有 Observable や独自の state-change listener は追加しない。

## v1 の範囲

対象は `agent.run.*`, `message.*`, `tool.*` の9種。Task、Working Set、Context、File Event、具体的な UI、SSE、永続化、Event replay は対象外。

将来の View を追加する場合は、`AgentUiState` に immutable collection/value を追加し、`DefaultAgentUiProjection.apply` の対応 Event branch と小さな accumulator update を追加する。Event handler framework は必要になるまで導入しない。
