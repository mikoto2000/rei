# 設計書: Web API 機能（Phase 1）

## 概要

本設計書は `.kiro/specs/web/requirements.md`（要件定義書）を実装可能な形に落とし込んだものである。
Phase 1 では Web API 基盤と Run API の確立を目的とし、以下の5エンドポイントのみを実装対象とする。

```text
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
- **カレントプロジェクトはグローバル状態から引きはがす**。クライアントが毎回リクエストに `projectId` を指定する。
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
| `RunController` | `GET /api/v1/runs/{runId}` / `POST /api/v1/runs/{runId}/cancel` |
| `SseController` | `GET /api/v1/runs/{runId}/events`（SSE） |
| `ChatSubmitService` | `POST /api/v1/chat` の application service。session / project 整合性を検証し、`RunRegistry.register(QUEUED)` と `ProjectRunQueue.enqueue(run)` を呼ぶ。新規 session の `sessionId`（conversationId）と `runId` / `turnId` を採番する |
| `RunService` | run の状態取得・cancel の application service |
| `RunRegistry` | run の状態（`RunStatus`）と metadata を保持する in-memory レジストリ。state transition は atomic に行い、terminal 状態から他状態への遷移を許可しない |
| `RunStatus` | `QUEUED` / `RUNNING` / `COMPLETED` / `FAILED` / `CANCELLED` の5状態 enum |
| `ProjectRunQueue` | projectId 単位の FIFO 直列キュー。`enqueue(run)` / `cancelQueued(runId)` を提供し、QUEUED の run を runId 指定で除去できる |
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
| `ProjectRegistry` | `resolveById(String id)` を追加し、登録済み projectId から作業ディレクトリを解決する |
| `ConversationInputRouter` | FIFO 直列化の責務を `ProjectRunQueue` へ移す。Router は active run の表示と `ProjectRunQueue` への委譲に留め、runId ベースの除去は `ProjectRunQueue.cancelQueued(runId)` に任せる |

---

## エンドポイント仕様

### `POST /api/v1/chat`

チャットを非同期実行し、run を作成する。

**Request**

```json
{
  "message": "このコードを調べて",
  "projectId": "rei",
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
  "projectId": "rei",
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

**異常終了の回収（terminal event 未発行の run）**

- `ProjectRunQueue` の executor 境界で、run の戻り値と例外を回収する。
- terminal event が発行されずに終了した run（`RuntimeException` 再送出等）を **`FAILED` として確定**し、`agent.run.failed` を発行する。
- これにより、`RUNNING` のまま残って SSE が終わらず purge も走らない、という状態を防ぐ。

**retention / purge**

- `RunRegistry` と `ReplayBuffer` は**同一の retention policy** に統一する。
  - run が terminal になった時点から **30 分保持**し、その後 purge する。
  - `RunRegistry` の purge と `ReplayBuffer` の purge を同時に行う。
- これにより、SSE 接続も `GET /runs/{runId}` も**同時に `404`** になる。

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
{ "message": "...", "projectId": "rei", "sessionId": "optional" }
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
  "projectId": "rei",
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
4. **Chat submit** — `ChatController` / `ChatSubmitService`、session / project 整合性。
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

### RunRegistry / 状態

- `GET /runs/{runId}` が `status` / `sessionId` / `turnId` / `projectId` / `startedAt` / `completedAt` / `failure` を返す。
- 存在しない runId → `404`。
- purge 済み runId → `404`（`410` は使わない）。
- `QUEUED` / `RUNNING` / `COMPLETED` / `FAILED` / `CANCELLED` の遷移。
- **state transition が atomic** で、terminal 状態から他状態へ遷移しない（cancel と terminal event の競合で `CANCELLED → COMPLETED` にならない）。
- **retention** — terminal から 30 分で `RunRegistry` / `ReplayBuffer` が同時に purge され、SSE も `GET /runs/{runId}` も同時に `404` になる。

### Project FIFO

- 異なる projectId の run は並行実行。
- 同一 projectId の run は FIFO 直列。
- `ProjectRunQueue.cancelQueued(runId)` で QUEUED の run をキューから除去できる。

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

### Cancel

- `RUNNING` → `202`。
- `QUEUED` → `202`（キューから除去して `CANCELLED`）。
- 既に terminal → `200`。
- 存在しない runId → `404`。
- **cancel 成功時のみ `agent.run.cancelled` を発行**する（atomic transition 成功時）。
- **既存 runner の `agent.run.failed` が `CANCELLED` 状態の run に来ても `agent.run.cancelled` として扱う**（Registry の terminal 状態が source of truth）。
- **異常終了の回収** — `ProjectRunQueue` の executor 境界で terminal event 未発行の run を `FAILED` として確定し、`agent.run.failed` を発行する（`RUNNING` のまま残らない）。
