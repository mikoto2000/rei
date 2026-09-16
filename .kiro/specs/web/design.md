# 設計書: Web API 機能（Phase 1）

## 概要

本設計書は `.kiro/specs/web/requirements.md`（要件定義書）を実装可能な形に落とし込んだものである。
Phase 1 では Web API 基盤と Run API の確立を目的とし、以下の6エンドポイントのみを実装対象とする。

```text
GET  /api/v1/projects
POST /api/v1/chat
GET  /api/v1/runs/{runId}
GET  /api/v1/runs/{runId}/events   (SSE)
POST /api/v1/runs/{runId}/cancel
GET  /actuator/health
```

`/history` `/search` `/briefing` `/feed` `/reminder` `/interest` `/memory` `/skill` `/image` `/summarize` `/profile` は後続 Phase で追加する。

## 設計方針

- **Shell UI と Web UI は application/core に並列にぶら下がる**。CLI と Web API は同じ application service を呼び、ShellCommand をそのまま HTTP 化しない。
- **Run API 中心**。`/chat` を中心に置かず、`/runs/{runId}` を中心にした共通 Run API を採用する。
- **deny by default**。Web API endpoint → application service を明示的に作り、ShellCommand を expose しない。
- **カレントプロジェクトはグローバル状態から引きはがす**。クライアントがチャット送信ごとに `projectId` を指定する。
- **Web API DTO を分離**する。内部型（`AgentEvent` 等）を直接 expose しない。

## アーキテクチャ

```text
[Web UI / 外部クライアント]
        │  HTTP / SSE
        ▼
[Web API レイヤ]  (Controller / Security / DTO)
        │
        ▼
[application service レイヤ]  (ChatSubmitService / RunService / ...)
        │
        ▼
[core レイヤ]  (ConversationInputRouter / AgentRunContext / ProjectRegistry / ...)
        │
        ▼
[event レイヤ]  (AgentEventBus / ReplayBuffer / AgentEvent)
```

- Web API レイヤは core の内部型を直接扱わず、DTO を介して application service を呼ぶ。
- application service は core の `ConversationInputRouter` 等を呼び出し、run のライフサイクルを管理する。

## コンポーネント

### 新規コンポーネント

| コンポーネント | 責務 |
| --- | --- |
| `WebApplication` | `REI_API_KEY` から `WebApplicationType` を判定し、SERVLET / NONE を決定する bootstrapping |
| `ApiKeyProperties` | `rei.api-key`（環境変数 `REI_API_KEY`）を保持する `@ConfigurationProperties` |
| `ApiKeyAuthenticationFilter` | `Authorization: Bearer <token>` を検証する `OncePerRequestFilter` |
| `SecurityConfig` | Spring Security filter chain を構成。`/actuator/health` のみ `permitAll` |
| `ChatController` | `POST /api/v1/chat` |
| `ProjectController` / `ProjectResponse` | `GET /api/v1/projects`。公開 DTO の `id` / `name` のみを返す |
| `ProjectQueryService` | `ProjectRegistry.list()` から登録済みプロジェクトの UUID と名前を取得する。レジストリとカレントプロジェクトを変更しない |
| `RunController` | `GET /api/v1/runs/{runId}` / `POST /api/v1/runs/{runId}/cancel` |
| `SseController` | `GET /api/v1/runs/{runId}/events`（SSE） |
| `ChatSubmitService` | `POST /api/v1/chat` の application service。session / project 整合性を検証し、`RunRegistry.register(QUEUED)` と `ProjectRunQueue.enqueue(run)` を呼ぶ。新規 session の `sessionId`（conversationId）と `runId` / `turnId` を採番する。**採番した `runId` を含む `AgentRunContext` を `ProjectRunQueue` と runner にそのまま渡し、内部で再採番しない**（`ConversationInputRouter.submit()` の runId 再採番は行わない）。`SessionRegistry` に sessionId → projectId を登録する |
| `RunService` | run の状態取得・cancel の application service |
| `SessionRegistry` | `sessionId`（conversationId）→ `projectId` の対応を submit 受理時に登録する in-memory レジストリ。ターンの有無と独立して session の存在・所属 project を判定する |
| `RunRegistry` | run の状態（`RunStatus`）と metadata を保持する in-memory レジストリ。state transition は atomic に行い、terminal 状態から他状態への遷移を許可しない |
| `RunStatus` | `QUEUED` / `RUNNING` / `COMPLETED` / `FAILED` / `CANCELLED` の5状態 enum |
| `ProjectRunQueue` | projectId 単位の FIFO 直列キュー。`enqueue(run)` / `cancelQueued(runId)` を提供し、QUEUED の run を runId 指定で除去できる。**AGENT run の FIFO に限定**する。`submitBackground`（`ExecutionType.AGENT` 以外）と `executeAuxiliary` は既存のまま `ConversationInputRouter` が管理し、`ProjectRunQueue` には入れない |
| `ReplayBuffer` | runId ごとにイベントを保持し、`subscribe(fromSequence)` で replay する bounded buffer |
| `SseBridge` | `AgentEventBus` と `SseEmitter` の間を bounded queue + executor で橋渡しする |
| `WebApiEventDto` | `AgentEvent` を Web API 用に変換した DTO |
| `ChatRequest` / `ChatResponse` | `POST /api/v1/chat` の request / response DTO |
| `RunResponse` | `GET /api/v1/runs/{runId}` の response DTO |

### 既存改修コンポーネント

| コンポーネント | 変更内容 |
| --- | --- |
| `AgentEventBus` | `lastSequence()` を `subscribe(fromSequence)` に拡張し、sequence ベースで replay 可能にする |
| `AgentEventType` | `AGENT_RUN_CANCELLED("agent.run.cancelled")` を新規追加 |
| `AgentEventFactory` | **`runCancelled(String runId, ErrorInformation error)`** を新設する。`agent.run.cancelled` を発行するためのファクトリメソッドが現状存在しないため、`RunService.cancel()` が発行できるようにする |
| `ProjectRegistry` | `resolveById(String id)` を追加し、登録済み projectId から作業ディレクトリを解決する |
| `ConversationInputRouter` | FIFO 直列化の責務を `ProjectRunQueue` へ移す。Router は active run の表示と `ProjectRunQueue` への委譲に留め、runId ベースの除去は `ProjectRunQueue.cancelQueued(runId)` に任せる。**`submit()` が内部で `UUID.randomUUID()` により runId を再採番する既存挙動は廃止し、`ChatSubmitService` が採番した `AgentRunContext` をそのまま受け取る**（レスポンス / Registry / ConversationTurnStore / AgentEvent の runId を一致させる） |
| `CommandCancellationService` | **`cancelRun(String runId)`** を追加し、runId 指定で実行中の runner を停止する（`cancel(state)` を runId で引く）。既存 `onCancel(runId, child)` は子コールバック登録のままで停止ハンドルにしない。**runId ごとの「キャンセル要求済み」フラグを `begin()` とは独立に保持**し、`begin()` 前に `cancelRun(runId)` が呼ばれても要求が消えないようにする。**pending cancellation のデータ構造（例: `Map<String, Boolean> pendingCancellations`）を新設**し、`cancelRun(runId)` は `runs` に無い runId に対して「キャンセル要求済み」を記録する。`begin()` はこの pending フラグを確認して消費し、立っている場合は runner を開始せず `CANCELLED` として確定する |

---

## エンドポイント仕様

### `GET /api/v1/projects`

`Authorization: Bearer <token>` 必須。未指定・不正なキーは `401 Unauthorized`。
`ProjectController` → `ProjectQueryService` → チャット受付と共通の `ProjectRegistry` の順に呼び出す。
リクエストごとに登録内容を読み、登録順で返す。パスを含む内部型は公開せず、`ProjectResponse` に変換する。

**Response `200 OK`**

```json
[
  { "id": "550e8400-e29b-41d4-a716-446655440000", "name": "rei" }
]
```

未登録なら `[]`。取得によるレジストリファイルの作成・変更はない。
返却された `id` をチャットの `projectId` に使う。プロジェクト名や任意パスは ID として受け付けない。

**PowerShell の利用例**（`$baseUrl` と `$headers` は起動先・API キーに合わせて設定済みとする）

```powershell
$projects = Invoke-RestMethod "$baseUrl/api/v1/projects" -Headers $headers
$projects | Format-Table id, name
# 表示された一覧から対象を選ぶ。以下は最初のプロジェクトを利用する例。
if (@($projects).Count -eq 0) { throw '先に Rei の Shell で /project add を実行してください' }
$body = @{ projectId = $projects[0].id; message = '構成を説明してください' } | ConvertTo-Json
$run = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/v1/chat" -Headers $headers `
    -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body))
```

### `POST /api/v1/chat`

チャットを非同期実行し、run を作成する。

**Request**

```json
{
  "message": "このコードを調べて",
  "projectId": "550e8400-e29b-41d4-a716-446655440000",
  "sessionId": "optional"
}
```

| フィールド | 型 | 必須 | 説明 |
| --- | --- | --- | --- |
| `message` | string | 必須 | ユーザー入力 |
| `projectId` | string | 必須 | 登録済みプロジェクト ID |
| `sessionId` | string | 任意 | 既存 session を継続する場合に指定 |

**sessionId semantics**

| ケース | 挙動 | HTTP status |
| --- | --- | --- |
| `sessionId` なし | 新規 session を作成 | `202` |
| `sessionId` あり | 既存 session を継続 | `202` |
| 指定 session の projectId ≠ request.projectId | `409 Conflict` | `409` |
| 未知の sessionId | `404 Not Found` | `404` |

**Response `202 Accepted`**

```http
HTTP/1.1 202 Accepted
Location: /api/v1/runs/{runId}
Content-Type: application/json
```

```json
{
  "runId": "uuid",
  "sessionId": "uuid",
  "turnId": "uuid"
}
```

**Error**

| 状況 | HTTP status |
| --- | --- |
| 未知の projectId | `404 Not Found` |
| 未知の sessionId | `404 Not Found` |
| session の projectId 不一致 | `409 Conflict` |
| 認証失敗 | `401 Unauthorized` |

### `GET /api/v1/runs/{runId}`

run の状態と metadata を取得する。

**Response `200 OK`**

```json
{
  "runId": "uuid",
  "status": "RUNNING",
  "sessionId": "uuid",
  "turnId": "uuid",
  "projectId": "550e8400-e29b-41d4-a716-446655440000",
  "startedAt": "2026-09-15T08:00:00Z",
  "completedAt": null,
  "failure": null
}
```

| フィールド | 型 | 説明 |
| --- | --- | --- |
| `status` | enum | `QUEUED` / `RUNNING` / `COMPLETED` / `FAILED` / `CANCELLED` |
| `startedAt` | string / null | 実行開始時刻（ISO-8601）。`QUEUED` では `null` |
| `completedAt` | string / null | 終了時刻（ISO-8601）。terminal 状態以外では `null` |
| `failure` | object / null | `FAILED` 時のエラー情報。`{ "type": "...", "message": "..." }` 形式。それ以外は `null` |

**Error**

| 状況 | HTTP status |
| --- | --- |
| 存在しない / purge 済み runId | `404 Not Found` |
| 認証失敗 | `401 Unauthorized` |

### `GET /api/v1/runs/{runId}/events`（SSE）

run のイベントを SSE でストリーミングする。

**Request header**

| ヘッダ | 説明 |
| --- | --- |
| `Last-Event-ID` | 任意。再開位置の sequence。`123` は 124 から replay |

**Response `200 OK`（`text/event-stream`）**

```text
event: agent.run.started
id: 100
data: {...}

event: message.delta
id: 101
data: {...}

event: agent.run.completed
id: 105
data: {...}
```

**terminal event** を受信した時点で `SseEmitter.complete()` によりストリームを閉じる。

| terminal event | 説明 |
| --- | --- |
| `agent.run.completed` | 正常完了 |
| `agent.run.failed` | 失敗 |
| `agent.run.cancelled` | キャンセル（`AgentEventType` に新規追加） |

**Error**

| 状況 | HTTP status |
| --- | --- |
| 存在しない / purge 済み runId | `404 Not Found` |
| replay gap（必要な履歴が失われている） | `409 Conflict` |
| 認証失敗 | `401 Unauthorized` |

### `POST /api/v1/runs/{runId}/cancel`

run をキャンセルする。冪等。

**Response**

| 状態 | HTTP status |
| --- | --- |
| `RUNNING` | `202 Accepted` |
| `QUEUED` | `202 Accepted`（キューから除去して `CANCELLED`） |
| 既に terminal | `200 OK` |
| 存在しない runId | `404 Not Found` |

### `GET /actuator/health`

認証不要（`permitAll`）。component detail / exception / 内部構成情報を返さない（`show-details=never` 相当）。

---

## 状態モデル（RunStatus）

```text
QUEUED ─────→ RUNNING ─────→ COMPLETED
   │              │
   │              ├────────→ FAILED
   │              │
   └──────────────┴────────→ CANCELLED
```

- `QUEUED` / `RUNNING` / `COMPLETED` / `FAILED` / `CANCELLED` の5状態。
- `COMPLETED` / `FAILED` / `CANCELLED` は terminal 状態。
- 同一 projectId の run は FIFO 直列のため、`QUEUED` と `RUNNING` を区別する。
- `QUEUED` の run への cancel も受け付け、キューから除去して `CANCELLED` とし `202` を返す。

### RunRegistry の状態遷移

`RunRegistry` は run ごとに以下の metadata を保持する。

```text
runId → { status, sessionId, turnId, projectId, startedAt, completedAt, failure }
```

- `submit` 時: `QUEUED` で登録。
- `ProjectRunQueue` の executor が実行開始（dequeue）: `QUEUED → RUNNING`。
- terminal event 受信: `RUNNING → COMPLETED / FAILED / CANCELLED`。
- cancel 受信: `QUEUED → CANCELLED`（キューから除去）/ `RUNNING → CANCELLED`。

**state transition の invariant**

- `RunRegistry` は state transition を **atomic** に行う（`compareAndSet` 等）。
- **terminal 状態（`COMPLETED` / `FAILED` / `CANCELLED`）から他の状態への遷移は許可しない**。
  - 例: cancel と terminal event が競合しても、`CANCELLED → COMPLETED` にはならない。

**cancel 時の terminal event 発行の契約**

- `RunService.cancel()` は `RunRegistry` の atomic transition に**成功した場合のみ** `agent.run.cancelled` を発行する。
  - `QUEUED → CANCELLED`: `ProjectRunQueue.cancelQueued(runId)` でキューから除去し、`agent.run.cancelled` を発行する。
  - `RUNNING → CANCELLED`: `agent.run.cancelled` を発行する。
- **既存 `ChatExecutionService` はキャンセル時に `agent.run.failed` を発行する**（`agent.run.cancelled` は発行しない）。この既存イベントは、Registry が `CANCELLED` に確定済みの run に対しては **SSE では `agent.run.cancelled` として扱う**。
- **契約: Registry の terminal 状態が source of truth**。SSE は Registry の状態に基づいて terminal を判定し、`agent.run.cancelled` で complete する。既存 runner の `agent.run.failed` が `CANCELLED` 状態の run に来ても、`agent.run.cancelled` として扱う（`CANCELLED → COMPLETED` 等の遷移は atomic transition により禁止）。
- **ReplayBuffer の replay 時も同じ変換契約を適用する**。`RunService.cancel()` が発行する `agent.run.cancelled` を正とし、既存 runner の `agent.run.failed` が `CANCELLED` 状態の run に来た場合は、**ライブ購読時と同様に replay 時も `agent.run.cancelled` として変換する**（または `agent.run.failed` を replay 対象から除外する）。再接続時に `agent.run.failed` がそのまま流れて `agent.run.cancelled` と矛盾するのを防ぐ。

**RUNNING cancel の実行停止（実際の runner 停止）**

`RunService.cancel()` が `RUNNING → CANCELLED` に遷移させるだけでは、実行中の runner は止まらない。**Registry の状態確定と実際の実行停止を連動させる**契約を定める。

- `RunService.cancel()` は、Registry の atomic transition に成功した後、**`CommandCancellationService.cancelRun(runId)`** を呼び、実行中の runner を停止する。
  - **既存 `CommandCancellationService.onCancel(runId, child)` は「子コールバックの登録」メソッドであり、停止要求を発行しない**。対象 State が未登録なら何もせず、`begin()` は新しい State に置き換える。そのため停止ハンドルとしては使わず、**runId 指定で停止を要求する `cancelRun(runId)` を新設**する。
  - `cancelRun(runId)` は `cancel(state)` と同様に、`cancellationRequested` を立て、登録済み children を実行し、`disposable` を dispose し、実行 thread を interrupt する。
- **実行停止の順序**: `RunRegistry` の `RUNNING → CANCELLED` 確定 → 停止要求（`CommandCancellationService.cancelRun(runId)`）→ `agent.run.cancelled` 発行。
- **開始前キャンセル要求の保持**: 既存 `CommandCancellationService.begin()` は `cancellationRequested` を `false` にリセットするため、dequeue 後〜`begin()` 前に cancel された場合に要求が消える。**runId ごとの「キャンセル要求済み」フラグを `begin()` とは独立に保持**し、`begin()` 前に `cancelRun(runId)` が呼ばれても要求が消えないようにする。**dequeue 時にキャンセル要求が既に立っている場合は、runner を開始せず `CANCELLED` として確定する**（`begin()` が新しい未キャンセル状態を作らない）。
- **同一 project の次の run は、実際の runner 終了（`finally` 完了）まで開始しない**。`ProjectRunQueue` は runner の終了を待ってから次の run を dequeue する（`CANCELLED` で SSE が閉じた後も実行が継続するのを防ぐ）。

**異常終了の回収（terminal event 未発行の run）**

- `ProjectRunQueue` の executor 境界で、run の戻り値と例外を回収する。
- terminal event が発行されずに終了した run（`RuntimeException` 再送出等）を **`FAILED` として確定**し、`agent.run.failed` を発行する。
- これにより、`RUNNING` のまま残って SSE が終わらず purge も走らない、という状態を防ぐ。

**retention / purge**

- `RunRegistry` と `ReplayBuffer` は**同一の retention policy** に統一する。
  - run が terminal になった時点から **30 分保持**し、その後 purge する。
  - `RunRegistry` の purge と `ReplayBuffer` の purge を同時に行う。
- これにより、SSE 接続も `GET /runs/{runId}` も**同時に `404`** になる。

**session の存在判定と所属 project（SessionRegistry）**

`ConversationTurnStore` だけでは session の存在と所属 project を判定できない（未登録 ID の `read()` は空一覧を返し、`Turn` に projectId がなく、ターン作成は実行開始時なので QUEUED のままの session は判定できない）。

- **`SessionRegistry`** を新設し、`sessionId`（conversationId）→ `projectId` の対応を **submit 受理時に登録**する。
- **session の存在判定はターンの有無と独立**に行う。`POST /api/v1/chat` の `sessionId` 存在判定（新規 / 継続 / `409` / `404`）は `SessionRegistry` で行う。
- **所属 project の不一致（`409`）** も `SessionRegistry` の sessionId → projectId 対応で判定する。
- **session の寿命**: `SessionRegistry` は run の purge とは独立に、session の最終アクセスから一定時間（例: 30 分）保持する。run が purge されても session は残り、継続可能。QUEUED のままキャンセルされた session も `SessionRegistry` に登録済みなので存在判定できる。

---

## SSE 設計

### イベント取りこぼし防止（ReplayBuffer）

`POST /api/v1/chat` → `202 + runId` → クライアントが `GET /api/v1/runs/{runId}/events` で SSE 接続、という間にイベントが発生すると取りこぼす問題に対処する。

```text
AgentEvent
    sequence
       ↓
ReplayBuffer (runId ごと)
       ↓
SSE subscribe(fromSequence)
```

- `AgentEventBus` の `lastSequence()` を `subscribe(fromSequence)` に拡張し、sequence ベースで replay 可能にする。
- **ReplayBuffer は bounded**。run 単位で最大 10,000 events、run 完了後 30 分保持し、その後 purge する。
- **ReplayBuffer は runId ごとにイベントを保持**する。ただし各イベントに付与される sequence は**プロセス全体で単調増加する global sequence**（run ごとに振り直さない）。
- クライアントは `Last-Event-ID` ヘッダで再開位置を指定。**`Last-Event-ID: 123` は 124 から**（指定 sequence より後）。
- SSE 接続時に対象 run が既に完了していた場合は、**ReplayBuffer に残っている terminal event を replay** してから complete する。synthetic な `run.status` event は生成しない（イベントモデルを増やさない）。

### replay gap の判定

- replay gap は単純に `Last-Event-ID < oldestSequence` だけを見るのではなく、**その run に対して「クライアントが受信すべきイベントが失われているか」**で判定する。
- `Last-Event-ID` が `latestSequence` より大きい場合（未来の sequence）は replay 対象がなく、gap とはみなさず現在の最新イベントから継続する。
- 実装上は各 run に `oldestRetainedSequence` / `latestSequence` / `evicted` のような metadata を持たせる。
- replay gap 時は `409 Conflict` で SSE 接続自体を拒否する（`event: replay-gap` は v1 では採用しない）。

### 非同期境界（EventBus 非ブロック）

`InMemoryAgentEventBus.publish()` は `synchronized` で listener を同期実行するため、SSE bridge の listener 内で `emitter.send()` すると遅いクライアントによって publisher thread（＝Agent 本体）がブロックされる。

```text
AgentEventBus
    ↓
bounded queue (上限 1000 件)
    ↓
SSE writer executor
    ↓
SseEmitter
```

- **SSE client queue が上限に達した場合、イベントを黙って drop せず、接続を error completion で終了する**。クライアントは `Last-Event-ID` で再接続し ReplayBuffer から再取得する。
- slow consumer / client disconnect / send IOException の扱いを定める。
- `onCompletion` / `onTimeout` / `onError` で Listener を必ず `unsubscribe` する（リーク防止）。

### replay とライブ購読の競合防止

`subscribe(fromSequence)` だけだと、履歴取得とライブ listener 登録の間に terminal event が発行されると、その接続は完了を受信できない。逆順だと replay とライブ配信の重複・順序逆転が起きる。

- **replay 境界の確定とライブ購読登録を一貫した同期境界で扱う**。
  - まず対象 run の `latestSequence` を取得し、replay 対象（`fromSequence` 〜 `latestSequence`）を確定する。
  - その境界を跨いだ後にライブ listener を登録する。
  - **境界以降に発行されたイベントは、replay の後に順序通りに配送する**（replay とライブ配信の重複・順序逆転を防ぐ）。
- これにより、replay 中に terminal event が発行されても、その接続は確実に terminal event を受信して complete できる。

**空 replay の終了条件（terminal event 受信済みの再接続）**

`Last-Event-ID` が terminal event の sequence と**同値**、または**未来**の sequence の場合、replay 対象は空になる。イベント受信だけを終了条件にすると、今後イベントが来ない完了済み run の接続が残る。

- **同期した購読開始処理**で、対象 run の terminal 状態と replay 対象を確認する。
- **terminal event が既に受信済みの場合（replay 対象が空）は、イベントを再送せず即 `complete()` する**。
- これにより、完了済み run への再接続で「イベントが来ないのに接続が残る」状態を防ぐ。
- 同値・未来の `Last-Event-ID` をテスト観点に含める。

**terminal 状態と空 replay の競合（状態確定とイベント格納の隙間）**

`CANCELLED` 確定と `agent.run.cancelled` 発行は別段階のため、その間に SSE が接続すると、Registry は terminal でも terminal event はまだ ReplayBuffer にない。例えば QUEUED → RUNNING 直後の cancel では、初回接続でも replay が空になり、空 replay の終了条件で**状態イベントを一度も送らず接続を閉じ得る**。購読開始だけを同期しても、状態確定からイベント発行までの隙間は解消されない。

- **終了判定に `terminalSequence` と ReplayBuffer への格納完了を含める**。購読開始処理で、対象 run の terminal 状態と、terminal event が ReplayBuffer に格納済みかを確認する。
- **状態確定と terminal event 格納を、購読開始と同じ同期境界で公開する**（状態確定 → terminal event 格納 → 購読開始の順序を保証する）。
- 格納前はライブ購読を維持し、**`terminalSequence` 以下を受信済みと確認できた場合だけ空 replay を即終了させる**。terminal event が未格納の場合は、ライブ購読で terminal event を受信してから complete する（状態イベントを一度も送らず閉じない）。

### heartbeat

- **`event: heartbeat` を 15〜30 秒周期で送信**する。
- nginx / reverse proxy / load balancer / NAT の idle timeout を回避し、クライアント側が「接続が生きている」ことを明示的に検知できる。
- **heartbeat は `AgentEvent` ではない**。`event: heartbeat` + `data: {}` だけを送信し、
  - `id` を付けない（global sequence を消費しない）
  - ReplayBuffer に保存しない
  - `Last-Event-ID` の global sequence と混ざらないようにする

---

## 認証・起動構成

### API キー認証

- **Spring Security** を採用（`spring-boot-starter-security`）。
- `Authorization: Bearer <token>` 形式の API キーを `OncePerRequestFilter` で検証する（単一 static API key のため）。
- **Web API は stateless** とし、Spring Security の HTTP session を使用しない（`SessionCreationPolicy.STATELESS`）。
- **`/api/**` は Bearer API key 認証のため CSRF protection を無効化する**（`csrf.disable()`）。API キー認証を通った POST が CSRF で `403` になるのを防ぐ。
- **API キー比較は constant-time comparison**（`MessageDigest.isEqual` 等）で行う。
- **API キーを request logging / exception message / AgentEvent / telemetry へ出さない**。
- `/actuator/health` のみ `permitAll`。その他の Actuator endpoint は外部公開しない。

### API キー未設定の定義

`REI_API_KEY` が未定義、空文字（`""`）、または trim 後空文字（`"   "`）の場合はすべて「未設定」として扱い、Web サーバーを起動しない。

### source of truth

- **Web API の有効化および API key の source of truth は環境変数 `REI_API_KEY` とする**。
- Spring 起動後は同値を `rei.api-key`（`ApiKeyProperties`）として扱う。
- `application.yml` の `rei.api-key` からは設定しない（bootstrap 時点で読めないため）。

### bootstrapping

```text
REI_API_KEY
   │
   ├─ blank? → WebApplicationType.NONE
   │
   └─ value  → WebApplicationType.SERVLET
                   ↓
                ApiKeyProperties (rei.api-key)
```

- `WebApplicationType` は ApplicationContext 作成**前**に決める必要がある。
- 起動時に `REI_API_KEY` 環境変数 → `SpringApplication` 作成 → SERVLET / NONE 判定 → `run()` という bootstrapping を行う。

### ネットワーク公開範囲

- `rei.web.bind-address=127.0.0.1` / `rei.web.port=8080` を正規設定とし、Spring Boot の `server.address` / `server.port` に反映する（二重管理を避ける）。
- LAN 公開したい場合のみ明示的に `rei.web.bind-address=0.0.0.0` 等を指定する。

---

## データ構造（DTO）

### `ChatRequest`

```json
{ "message": "...", "projectId": "550e8400-e29b-41d4-a716-446655440000", "sessionId": "optional" }
```

### `ChatResponse`

```json
{ "runId": "uuid", "sessionId": "uuid", "turnId": "uuid" }
```

- `sessionId` は既存の `conversationId` に対応する（session ごとに一意な conversationId を採番）。
- `turnId` は `runId` と同一値（既存コードに独立した `turnId` は存在しない）。

### `RunResponse`

```json
{
  "runId": "uuid",
  "status": "RUNNING",
  "sessionId": "uuid",
  "turnId": "uuid",
  "projectId": "550e8400-e29b-41d4-a716-446655440000",
  "startedAt": "...",
  "completedAt": null,
  "failure": null
}
```

### `WebApiEventDto`

`AgentEvent` を Web API 用に変換した DTO。`AgentEvent` をそのまま JSON 化せず、外部 schema を固定する。

```text
AgentEvent
    ↓
WebApiEventDto
    ↓
JSON / SSE
```

---

## 実装順序（TDD）

レビューが示した段階的な組み方を採用する。

```text
Web 起動
 → Security
 → RunRegistry
 → Chat submit
 → Project FIFO
 → Cancel
 → SSE
 → Replay
```

1. **Web 起動** — `WebApplication` bootstrapping、`ApiKeyProperties`、`WebApplicationType` 判定。
2. **Security** — `ApiKeyAuthenticationFilter`、`SecurityConfig`、`/actuator/health` permitAll、STATELESS + CSRF disable。
3. **RunRegistry** — `RunStatus` enum、run の状態遷移（atomic / terminal immutable）、`GET /runs/{runId}`。
4. **Chat submit** — `ChatController` / `ChatSubmitService`、session / project 整合性、`SessionRegistry` への sessionId → projectId 登録、**採番した `runId` を含む `AgentRunContext` を `ProjectRunQueue` / runner にそのまま渡す（内部で再採番しない）**。
5. **Project FIFO** — `ProjectRunQueue`（`enqueue` / `cancelQueued`）、projectId 単位直列化。
6. **Cancel** — `POST /runs/{runId}/cancel`、QUEUED / RUNNING の cancel。
7. **SSE** — `SseController`、`SseBridge`、bounded queue + executor。
8. **Replay** — `ReplayBuffer`、`subscribe(fromSequence)`、`Last-Event-ID`、replay gap。

> `ChatSubmitService` が `RunRegistry.register(QUEUED)` と `ProjectRunQueue.enqueue(...)` を必要とするため、RunRegistry を先に作る方が Red → Green の粒度を作りやすい。また Cancel を SSE より前に置くことで、`QUEUED → RUNNING → CANCELLED` という Run lifecycle を完成させてから SSE で観測する順序になる。

---

## テスト観点

### 認証・起動

- `REI_API_KEY` 未設定 → Web サーバー起動しない（`WebApplicationType.NONE`）。
- `REI_API_KEY` 空文字 / trim 後空文字 → 未設定扱い。
- 正しい API キー → `200`。誤った API キー → `401`。
- `/actuator/health` は認証不要で `200`。component detail を返さない。
- API キーがログ / 例外 / イベントに出力されない。

### Chat submit

- `sessionId` なし → 新規 session、`202` + `Location` ヘッダ。
- `sessionId` あり → 既存 session 継続。
- session の projectId 不一致 → `409`。
- 未知の sessionId → `404`。
- 未知の projectId → `404`。
- **session 存在判定は `SessionRegistry` で行う**（ターンの有無と独立。QUEUED のままの session も判定できる）。
- **採番した `runId` がレスポンス / `RunRegistry` / `ConversationTurnStore` / `AgentEvent` で一致する**（`ConversationInputRouter` が内部で再採番しない）。

### RunRegistry / 状態

- `GET /runs/{runId}` が `status` / `sessionId` / `turnId` / `projectId` / `startedAt` / `completedAt` / `failure` を返す。
- 存在しない runId → `404`。
- purge 済み runId → `404`（`410` は使わない）。
- `QUEUED` / `RUNNING` / `COMPLETED` / `FAILED` / `CANCELLED` の遷移。
- **state transition が atomic** で、terminal 状態から他状態へ遷移しない（cancel と terminal event の競合で `CANCELLED → COMPLETED` にならない）。
- **retention** — terminal から 30 分で `RunRegistry` / `ReplayBuffer` が同時に purge され、SSE も `GET /runs/{runId}` も同時に `404` になる。
- **session 存在判定** — `SessionRegistry` が sessionId → projectId を保持し、ターン未作成（QUEUED のまま）の session も存在判定できる。run が purge されても session は残る。

### Project FIFO

- 異なる projectId の run は並行実行。
- 同一 projectId の run は FIFO 直列。
- `ProjectRunQueue.cancelQueued(runId)` で QUEUED の run をキューから除去できる。
- **`ProjectRunQueue` は AGENT run の FIFO に限定**し、`submitBackground` / `executeAuxiliary` は `ConversationInputRouter` が管理する（`ProjectRunQueue` に入れない）。

### SSE / Replay

- `Last-Event-ID: 123` → 124 から replay。
- run 完了後の SSE 接続 → **ReplayBuffer に残っている terminal event を replay** して complete（synthetic event を生成しない）。
- terminal event（`agent.run.completed` / `failed` / `cancelled`）でストリームを閉じる。
- replay gap → `409 Conflict`。
- queue overflow → drop せず error completion。
- heartbeat が 15〜30 秒周期で送信される。
- **heartbeat は `id` を持たず、global sequence を消費せず、ReplayBuffer に保存されない**。
- `onCompletion` / `onTimeout` / `onError` で unsubscribe。
- **replay とライブ購読の競合** — replay 境界の確定とライブ listener 登録を一貫した同期境界で扱い、replay 中に terminal event が発行されても確実に受信して complete する。replay とライブ配信の重複・順序逆転がない。
- **空 replay の終了条件** — `Last-Event-ID` が terminal event の sequence と同値 / 未来の場合、replay 対象が空でも即 `complete()` する（イベントが来ないのに接続が残らない）。
- **terminal 状態と空 replay の競合** — `CANCELLED` 確定と `agent.run.cancelled` 格納の間に SSE 接続しても、状態イベントを一度も送らず閉じない。terminal event が ReplayBuffer に未格納の場合はライブ購読で受信してから complete する。`terminalSequence` 以下を受信済みと確認できた場合だけ空 replay を即終了する。
- **replay 時の変換契約** — `CANCELLED` 状態の run への再接続で、ReplayBuffer の replay 時に既存 runner の `agent.run.failed` が `agent.run.cancelled` として変換される（または除外される）。`agent.run.failed` がそのまま流れて `agent.run.cancelled` と矛盾しない。

### Cancel

- `RUNNING` → `202`。
- `QUEUED` → `202`（キューから除去して `CANCELLED`）。
- 既に terminal → `200`。
- 存在しない runId → `404`。
- **cancel 成功時のみ `agent.run.cancelled` を発行**する（atomic transition 成功時）。
- **既存 runner の `agent.run.failed` が `CANCELLED` 状態の run に来ても `agent.run.cancelled` として扱う**（Registry の terminal 状態が source of truth）。
- **異常終了の回収** — `ProjectRunQueue` の executor 境界で terminal event 未発行の run を `FAILED` として確定し、`agent.run.failed` を発行する（`RUNNING` のまま残らない）。
- **RUNNING cancel の実行停止** — `RUNNING → CANCELLED` 確定後に `CommandCancellationService.cancelRun(runId)` で実際の runner を停止する。dequeue 後〜`begin()` 前に cancel された場合、runner を開始せず `CANCELLED` として確定する。同一 project の次の run は runner 終了まで開始しない。
- **`cancelRun(runId)` による停止** — `onCancel(runId, child)` は子コールバック登録のままで停止ハンドルにしない。`cancelRun(runId)` が `cancellationRequested` を立て、children 実行 / disposable dispose / thread interrupt を行う。
- **開始前 cancel の競合** — `begin()` 前に `cancelRun(runId)` が呼ばれても、runId ごとのキャンセル要求フラグが `begin()` のリセットで消えない。dequeue 直後に cancel する競合テストで、runner が開始されず `CANCELLED` になることを確認する。
- **`agent.run.cancelled` の発行経路** — `AgentEventFactory.runCancelled(runId, error)` が存在し、`RunService.cancel()` が atomic transition 成功時にそれを呼んで `agent.run.cancelled` を発行する。
