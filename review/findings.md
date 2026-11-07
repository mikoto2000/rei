# 指摘詳細

各指摘の根拠・影響・推奨対応。行番号はレビュー時点の作業ツリーに対応する。

---

## M-1: `stream()` の所有権解決がイベント経路と計測経路で非対称

**対象**: `src/main/java/dev/mikoto2000/rei/llm/AgentEventChatModel.java`

**根拠**

`stream()` は購読前に呼び出し元の所有権を退避している。

```java
// Retain the caller before subscribeOn or provider callbacks can lose its lexical scope.
var invocationOwner = owner(prompt);
return Flux.deferContextual(
    context -> {
      var owner = invocationOwner == null ? owner(prompt) : invocationOwner;
      var publisher = publisher(owner);
      String runId = owner == null ? null : owner.runId();
```

イベント発行はこの退避済み `owner` を使うため、別スレッドで購読しても呼び出し元の Run に紐づく。

一方、計測とキャンセル判定は退避値を使わず、その場で `owner(prompt)` を再解決している。

```java
var measured = measurement(prompt, requestId, trace);
```

```java
private dev.mikoto2000.rei.timing.TimingExecution.SpanLease measurement(
    Prompt prompt, String request, dev.mikoto2000.rei.timing.TimingAttempt trace) {
  if (timing == null || !timing.enabled()) return null;
  var owner = owner(prompt);
  if (owner == null) return null;
```

`owner(Prompt)` は toolContext を見た後、`AgentRunScope.current()`（ThreadLocal）へフォールバックする。

```java
return dev.mikoto2000.rei.core.chat.AgentRunScope.current();
```

**影響**

所有権が toolContext ではなく呼び出し元スレッドの lexical scope だけで与えられている場合、`subscribeOn(boundedElastic())` 後の購読スレッドでは `AgentRunScope.current()` が `null` になる。このとき:

- `measurement(...)` が `null` を返し、LLM Span が記録されない（`/timing` に当該呼び出しが出ない）
- `observeChunk(...)` 内の `owner(prompt)` も `null` になり、`timing.markRun(...)` が呼ばれない
- `cancelled(prompt)` は toolContext のみを見るため `false` を返し、`doOnCancel` が `CANCELLED` ではなく `DISCONNECTED` を記録する

イベント（`llm.request.started` 等）は正しい Run に届くのに、計測だけ欠けるという不整合になる。今回の修正が対象とした「非同期境界で所有権を失う」問題の、計測側に残る同種の穴。

**推奨対応**

`measurement` / `observeChunk` / `cancelled` にも解決済み `owner` を渡す。例えば `measurement(owner, prompt, requestId, trace)` のように引数化し、`owner(prompt)` の再解決を `call()` 経路だけに限定する。

**検証状況**

`AgentEventChatModelTest#asyncSignalsKeepTheCallingRunEvenOnAnotherClientsThread` はイベントの `runId` / `sessionId` / `projectId` のみを検証しており、Timing Span の有無は検証していない。この指摘はテストでは捕捉されない。

---

## M-2: Working Set の注入位置が全経路で変更された

**対象**: `src/main/java/dev/mikoto2000/rei/core/working/WorkingSetAdvisor.java`

**状態**: 確認済み（TensorFold / DeepSeek-V4.1-Flash-1017）

**根拠**

変更前は、`rei.contextProjection` でない通常経路でユーザーメッセージ本文の先頭へ連結していた。

```java
UserMessage contextualMessage =
    userMessage.mutate().text(workingSetContext + "\n\n" + userMessage.getText()).build();
```

変更後は全経路で、metadata `rei.workingSet` を持つ独立した `SystemMessage` を末尾に追加する。

```java
var messages = new ArrayList<Message>(request.prompt().getInstructions());
messages.removeIf(m -> Boolean.TRUE.equals(m.getMetadata().get("rei.workingSet")));
messages.add(
    org.springframework.ai.chat.messages.SystemMessage.builder()
        .text(workingSetContext)
        .metadata(java.util.Map.of("rei.workingSet", true))
        .build());
```

**影響**

意図（Prefix 安定性のため可変情報を末尾へ）は `docs/llm-prompt-cache.md` と整合しており妥当。ただし影響範囲が広い。

- ユーザーメッセージ本文に Working Set が含まれなくなったため、「ユーザーの発言」として解釈していたプロンプトやテスト前提が変わる
- 末尾が `SystemMessage` になるため、最後が user であることを前提とする Provider / テンプレートで挙動が変わり得る
- `ContextAssembler` を通らない経路（Advisor 単体利用、テスト、将来の呼び出し）では、この配置がそのまま Provider へ渡る

**検証結果**

`main` の `7f32022b` からビルドした Rei 本体で、通常時の実リクエストが `user → system` で終わり、末尾の `system` に Working Set が含まれることを確認した。Provider は HTTP 200 を返し、Rei はツールを呼ばず期待したファイル名を返して Run を完了した。検証範囲は上記の接続先とモデル。 [実行証跡](./test-evidence.md#rei-本体からの実-provider-検証)

**補足（既存挙動）**

`rei.contextProjection` 経路では、Working Set が空でも空 content の `SystemMessage` を追加する。これは変更前から同じで、今回の回帰ではない。`ContextAssembler.withWorkingSetSnapshot` を通る場合は空表示へ置換されるが、通らない経路では空メッセージが残る。

---

## L-1: 型付き拒否への変換が `listFile` 単一引数版のみ

**対象**: `src/main/java/dev/mikoto2000/rei/core/Tools.java`

**根拠**

```java
List<String> listFile(String baseDir) throws IOException, InterruptedException {
  try {
    return listFile(baseDir, currentWorkingDirectory());
  } catch (FileSnapshots.ExcludedInventoryPathException excluded) {
    throw new dev.mikoto2000.rei.toolcontract.ToolReadRejectedException(
        "PROJECT_PATH_EXCLUDED", ...);
  }
}
```

2引数版 `listFile(String, Path)` は変換しない。また `FileSnapshots.resolve(...)` も `resolveInventoryPath(...)` を呼ぶため、`readFile` 系は同じ例外を投げ得るが、型付き拒否にはならない。

**影響**

`StagnationChatModel` の修正フィードバックは `ToolReadRejectedException` を条件にしているため、`listFile` 以外の経路では「引数を直して再試行」の案内が出ず、汎用エラーとして扱われる。

**推奨対応**

変換を `resolveInventoryPath` の呼び出し境界（`FileSnapshots` 側）へ寄せるか、`listFile` の2引数版にも同じ変換を入れる。

---

## L-2: ローカル変数がメソッド名を隠蔽

**対象**: `src/main/java/dev/mikoto2000/rei/llm/AgentEventChatModel.java`

**根拠**

```java
var owner = invocationOwner == null ? owner(prompt) : invocationOwner;
var publisher = publisher(owner);
```

**影響**

コンパイルは通る（`owner(prompt)` はメソッド呼び出しとして解決される）が、`owner` が変数かメソッドか読み手に分かりにくい。

**推奨対応**

`resolvedOwner` / `eventPublisher` などへ改名する。

---

## L-3: `measurement` が所有権を二重解決

**対象**: `src/main/java/dev/mikoto2000/rei/llm/AgentEventChatModel.java`

**状態**: 解消済み

**指摘時の根拠**

```java
private ... measurement(Prompt prompt, String request) {
  if (timing == null || !timing.enabled()) return null;
  var owner = owner(prompt);
  if (owner == null) return null;
  return measurement(prompt, request, null);
}
```

3引数版も先頭で `owner(prompt)` を呼ぶ。

**確認結果**

M-1 の修正で、`measurement` は呼び出し側が解決した `runId` を受け取るようになった。二重解決を起こしていたオーバーロードは削除されている。 [修正 PR #417](https://github.com/mikoto2000/rei/pull/417) · [main 取り込み PR #434](https://github.com/mikoto2000/rei/pull/434)

---

## L-4: 末尾 `SystemMessage` の Provider 互換性

**対象**: `src/main/java/dev/mikoto2000/rei/core/contextbudget/ContextAssembler.java`

**状態**: 確認済み（TensorFold / DeepSeek-V4.1-Flash-1017）

**根拠**

```java
private Prompt withWorkingSetSnapshot(Prompt prompt) {
  ...
  messages.add(
      SystemMessage.builder().text(snapshot).metadata(marker.getMetadata()).build());
  return new Prompt(messages, prompt.getOptions());
}
```

`project(...)` の末尾で呼ぶため、最後の要素が `ToolResponseMessage` の場合はその後ろに `SystemMessage` が付く。

**検証結果**

`main` の `7f32022b` からビルドした Rei 本体で `readMultiFile` を実行し、次の実リクエストが `assistant → tool → system` で終わることを確認した。末尾の `system` には読み取り後の Working Set が含まれていた。Provider は HTTP 200 を返し、Rei は期待したファイル内容を返して Run を完了した。検証範囲は上記の接続先とモデル。 [実行証跡](./test-evidence.md#rei-本体からの実-provider-検証)

---

## L-5: 推定のたびに Working Set を再描画

**対象**: `src/main/java/dev/mikoto2000/rei/core/contextbudget/ContextAssembler.java`

**状態**: 対応済み

**指摘時の根拠**

修正前は、`assembleLocked` が `estimate(project(raw, h, a))` を複数回呼び、投影のたびに `withWorkingSetSnapshot(...)` が `workingSet.get()` を実行していた。

```java
String snapshot = workingSet == null ? marker.getText() : workingSet.get();
```

**影響**

推定時と最終投影時で Working Set が変わると、推定値と実際の送信内容がずれる。また同じ内容を複数回描画する分のコストがかかる。

**対応結果**

組み立てごとに Working Set を一度取得し、トークンの見積もりと最終プロンプトで同じスナップショットを使うようにした。次のモデル呼び出しでは取り直すため、ツール実行後の更新も反映される。取得回数と次回の更新を確認する回帰テストを追加した。 [修正 PR #499](https://github.com/mikoto2000/rei/pull/499) · [回帰テスト結果](./test-evidence.md#l-5-の回帰テスト)

---

## L-6: `llm.request.failed` 即時描画後のヘッダ再表示

**対象**: `terminal/cli/src/main/java/dev/mikoto2000/rei/cli/RunEventOutput.java`

**根拠**

```java
if (active != null
    && !type.startsWith("tool.")
    && !type.equals("attention.required")
    && !type.equals("llm.request.failed")
    && !Set.of(...).contains(type)) {
  notices.add(...);
  return;
}
flush();
renderNotice(type, payload, timestamp, projectId);
```

`flush()` は `active = null` にする。

```java
void flush() {
  ...
  active = null;
  ...
}
```

**影響**

失敗を即時表示する修正自体は妥当（思考ストリームが中断したまま失敗が隠れる問題を解消）。ただし同じ `thinkingId` の delta が続くと `!key.equals(active)` が真になり、`=== thinking (HH:mm:ss) ===` ヘッダが再度出力され得る。

**推奨対応**

失敗描画後に `active` を保持する、または「ヘッダ出力済み」を別フラグで管理する。

---

## L-7: 予算超過メッセージが claim 済みの場合に不正確

**対象**: `src/main/java/dev/mikoto2000/rei/review/ReviewTools.java`

**根拠**

```java
var claim = workItems().running(taskScope(owner), taskId, owner.runId());
if (claim == null) claim = claimReviewWorkItem(taskId, tokenBudget);
if (claim == null) return null;
var page = readReviewWorkItem(claim.item().id(), claim.attempt());
if (estimate(page) > budget)
  throw new ToolReadRejectedException("CONTEXT_BUDGET_INSUFFICIENT",
      "The existing frozen claim does not fit this request. ... No new work was claimed or assessed.");
```

**影響**

`claim == null` の分岐で新規 claim した場合でも「No new work was claimed」と表示される。実際には claim 済みで、RUNNING の作業単位が残る。モデルが「何も確保されていない」と誤解し、同じ taskId で再 claim を試みる可能性がある。

**推奨対応**

新規 claim か既存 claim かを区別してメッセージを分ける。新規の場合は「claim は作成済み。ID を保持したまま、文脈を圧縮して再読してください」と案内する。

---

## 確認したが問題なしとした点

- `RunExecutionContext` の `RESUME_REVIEW_READ_TOOLS` 定数化は、4箇所の重複条件を1つにまとめており、`reviewPageReadKey` の検証も `[^:]+:[0-9a-f]{64}` + ツール名照合へ拡張されている。`readReviewWorkItem` / `readNextReviewWorkItem` の両方が対象になり、テストでも両方のケースが通っている。
- `WorkingSet.renderForPrompt()` のパス昇順ソートは表示のみを安定化し、`getFiles()` のアクセス順と LRU 追い出しは維持されている。
- `RuntimeContextAdvisor` の `Request start time` 固定は、`{...}` の `replace` による埋め込みで、`formatted` の `%` 解釈問題も避けている。
- `FileSnapshots.ExcludedInventoryPathException` は `IOException` のサブクラスで、既存の `throws IOException` 契約を壊さない。
- `RunEventOutput` の `llm.request.failed` 除外は、`Set.of(...)` の抑制対象とは独立した条件で、他のイベント抑制に影響しない。
