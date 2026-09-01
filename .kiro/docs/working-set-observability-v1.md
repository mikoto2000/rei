# Working Set Observability v1.1

## Purpose

Working Set の検索、候補化、選択、実追加、削除を Agent Event Bus と Shell から観測可能にする。
この機能は instrumentation のみであり、検索、ranking、relevance、選択、LRU eviction、最大サイズ、
prompt、LLM 呼び出し回数を変更しない。

## Existing API investigation

実装前から `working_set.item.added` と `working_set.item.removed` の Event Type、payload、factory は存在した。
これらは型と factory のみで、Working Set の実処理から publish されず、Shell renderer も表示していなかった。
同義のイベントは新設せず、この2種類を実処理へ統合した。

Working Set item (`FileReference`) は path、access type、最終 access/modify 時刻を保持するが、LLM が生成した
自然言語 selection reason は保持しない。Observability のための reason 生成や LLM 呼び出しは追加しない。
added reason には追加時点に既に確定している `read` / `write` / `edit` / `create` を使用する。
removed reason には mutation 境界で既に確定している `explicit removal` / `missing file` /
`capacity eviction` / `clear` を使用する。reason がない event を Shell が受けた場合は reason を表示せず、
`unknown` を作らない。

## Events and boundaries

| Event | Boundary | Payload |
|---|---|---|
| `working_set.item.added` | `WorkingSet.addOrTouch` が新規 path を追加した直後 | itemId, kind, identifier, path, reason |
| `working_set.item.removed` | `WorkingSet` が存在する path を実際に除去した直後 | itemId, reason |
| `working_set.search.started` | validated `searchAndRead` が backend 検索を開始する直前 | searchId, query, strategy, workingSetSizeBefore |
| `working_set.search.completed` | 候補選択と read/Working Set 更新が終了した直後 | searchId, durationMs, hitCount, candidateCount, selectedCount, alreadyPresentCount, workingSetSizeBefore, workingSetSizeAfter |

検索イベントに hit 一覧、候補本文、ファイル本文、embedding、score は格納しない。query は一般的な secret assignment を
redactし、control character を除去して whitespace を畳み、payload では最大500文字、Shell では最大120文字に制限する。item reason は Shell で
1行化し最大120文字に制限する。

`searchAndRead` は現在、Working Set を作る唯一の「backend 検索 → 一意ファイル候補 → maxFiles による選択 →
read 成功時の追加」という共通 lifecycle である。`grepMultiQuery` 単体は Working Set を変更しないため、
Working Set search event を発行しない。Tool Event は tool invocation を、Working Set Event は Working Set に対する
成果を表す。

## Metric semantics

- `hitCount`: grep backend が正常な query result として返した matched line の総数。
- `candidateCount`: matched line を path で重複排除した一意ファイル数。選択評価の対象数。
- `selectedCount`: candidate のうち既存 `maxFiles` 上限内で read 対象に選ばれた数。read error や既存 item を含み得る。
- `alreadyPresentCount`: selected file のうち search 開始時の Working Set に既に存在した数。
- `workingSetSizeBefore/After`: lifecycle 開始直前と完了直後の Working Set file 数。
- `durationMs`: lifecycle の monotonic elapsed time。0以上。

従って `selectedCount` は actual added 数ではない。`actual added = selected - already present - read failures` が基本だが、
capacity eviction が同時に起きる場合は size 差とも一致しない。実追加は `working_set.item.added` を集計する。

## Correlation

started/completed は同じ `searchId` を payload と envelope `correlationId` に持つ。search 中に実際に新規追加された
`working_set.item.added` も同じ `correlationId` を持つ。重複 touch では added event を発行しない。
大規模な tracing infrastructure や parent event は追加していない。

## Shell rendering

```text
[working-set] → search "ToolCallbackProvider"
[working-set] + ToolEventCallbackProvider.java (read)
[working-set] ✓ 18 hits → 5 candidates → 2 selected, 1 already present (91 ms)
[working-set] - OldContext.java (capacity eviction)
```

表示は既存 `ShellEventOutput` を経由するため JLine の入力行制御を再利用し、Working Set から stdout へ直接書かない。

## Reuse

専用の reuse phase、relevance 判定結果、reused count は既存実装に存在しない。現在の reuse は Working Set を prompt に
提示し、既知 path を直接 read するよう LLM に指示する形である。イベントのためだけに判定アルゴリズムを作ると既存動作を
変えるため、`working_set.reuse.completed` は実装しない。

## Metrics and non-goals

event は search 回数/時間、hit→candidate、candidate→selected、selected→actual added、duplicate rate、churn、
reason 傾向を後から集計できる。metrics backend、永続化、dashboard、replay、専用 UI pane、全 hit/candidate 表示、
新しい reason/reuse/ranking/relevance/search algorithm は v1.1 の対象外である。

## Manual smoke test

1. Shell で source symbol を探して読む依頼を実行する。
2. search started、集計 summary、実際に読まれた新規 file の added が表示されることを確認する。
3. 同じ file を含む検索を再実行し、`already present` が増え、その file の added が重複表示されないことを確認する。
4. max size を超える test/integration scenario では capacity eviction の removed を確認する。

外部 LLM が利用できない環境では `ToolsTest` と `ShellAgentEventRendererTest` が同じ lifecycle を deterministic に検証する。
