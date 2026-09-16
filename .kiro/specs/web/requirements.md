# 要件定義書: Web API 機能

## はじめに

本機能は、AI エージェント「rei」の機能を HTTP 経由で外部から利用可能にする Web API を提供する。
既存の CLI（シェル）を主インターフェースとして維持しつつ、Web API を追加インターフェースとして公開する。
認証には API キー（`Authorization: Bearer <token>`）を用い、未設定時は Web サーバー自体を起動しない。

本 Web API は単なる「CLI の HTTP ラッパー」ではなく、`application/core` に対して Shell UI と Web UI が並列にぶら下がる構造を目指す。
CLI と Web API は同じ application service を呼び、ShellCommand をそのまま HTTP 化しない。

## Phase 1 実装範囲

Phase 1 では Web API 基盤と Run API の確立を目的とし、**以下のみを実装対象とする**。

```text
GET  /api/v1/projects
POST /api/v1/chat
GET  /api/v1/runs/{runId}
GET  /api/v1/runs/{runId}/events
POST /api/v1/runs/{runId}/cancel
GET  /actuator/health
```

`/history` `/search` `/briefing` `/feed` `/reminder` `/interest` `/memory` `/skill` `/image` `/summarize` `/profile` は**後続 Phase で追加する**。
公開可否の方針は本要件に従うが、**Phase 1 の実装対象外**とする。

Phase 1 で固める Web API の土台は以下の通り。

- Security（API キー認証）
- Web サーバー起動条件（API キー未設定時は起動しない）
- Project isolation
- Session isolation
- Run concurrency（FIFO 直列化）
- SSE replay
- SSE disconnect / reconnect
- Cancel

## 用語集

- **Web API**: HTTP 経由で rei の機能を呼び出す REST エンドポイント群。
- **API キー**: `Authorization: Bearer <token>` 形式で送信される認証トークン。環境変数 `REI_API_KEY` で設定する。
- **SSE**: Server-Sent Events。サーバーからクライアントへイベントを逐次ストリーミングする仕組み。
- **SseEmitter**: Spring MVC で SSE を実装するためのクラス。
- **runId**: 非同期実行されるエージェント実行を識別する ID。`AgentRunContext` が保持する。
- **sessionId / turnId**: 会話セッションとターンを識別する ID。`AgentRunContext` が保持する。
- **AgentEventBus**: プロセス内イベントバス。`AgentEvent` を購読・配信する。
- **AgentEvent**: `runId` / `sessionId` / `turnId` / `projectId` を共通 Envelope として持つイベント。`agent.run.*` / `message.*` / `tool.*` / `working_set.*` / `skill.*` などに汎用化されている。
- **sequence**: `AgentEventBus` がイベントに割り当てる単調増加の通し番号。**プロセス全体のグローバル sequence**（run 単位ではない）。SSE の replay に用いる。
- **ReplayBuffer**: イベント履歴を保持し、`subscribe(fromSequence)` で過去イベントを replay する仕組み。
- **projectId**: 事前登録されたプロジェクトを識別する ID。Web API は任意のファイルシステムパスを受け付けない。
- **カレントプロジェクト**: エージェントが作業対象とするプロジェクト。
- **RunStatus**: run の状態を表す enum。`QUEUED` / `RUNNING` / `COMPLETED` / `FAILED` / `CANCELLED` の5状態を持つ。同一 projectId の run は FIFO 直列のため `QUEUED` と `RUNNING` を区別する。

---

## 前提・設計方針

### API 基本形（Run API 中心）

`/chat` を中心に置くのではなく、**`/runs/{runId}` を中心にした共通 Run API** を採用する。
`AgentEvent` 自体が chat 専用ではなく汎用化されているため、将来 `/image` `/summarize` `/briefing` も async run 化する際に共通化できる。

```text
GET  /api/v1/projects
POST /api/v1/chat              → runId / sessionId / turnId を返す
GET  /api/v1/runs/{runId}
GET  /api/v1/runs/{runId}/events   (SSE)
POST /api/v1/runs/{runId}/cancel
```

- `/cancel` は `runId` 必須とする。CLI の「現在動いているものを cancel」は Web では成立しないため。
- 既存の `AgentRunContext`（runId / sessionId / turnId / projectId）と `AgentEvent` の Envelope がそのまま対応する。

### 公開範囲（deny by default）

Web API は ShellCommand をそのまま expose せず、**Web API endpoint → application service** を明示的に作る。
サブコマンドを持つコマンド（`/memory` `/profile` `/interest` `/feed` `/reminder` など）は、`read` / `write` / `delete` を個別に判断する。

**deny by default** とし、後から危険な操作が追加されたときに自動的に公開されないようにする。

| コマンド | サブコマンド | 公開可否 | 備考 |
| --- | --- | --- | --- |
| `/sh` | — | 非公開 | シェル実行。外部公開は危険 |
| `/config` | `init --force` | 非公開 | 設定ファイルを上書き生成。API キー破壊の恐れ |
| `/project` | `add` / `remove` | 非公開 | 作業ディレクトリの登録・削除。任意パス操作 |
| `/project` | `list` | 公開（Phase 1） | `GET /api/v1/projects`。登録済み UUID と名前のみ返す |
| `/project` | `cd` | **非公開（Web API から削除）** | Web ではチャット送信ごとに `projectId` を指定するため不要 |
| `/subagent` | `init` / `validate` | 非公開 | ファイル書き込み・任意パス読み込み |
| `/embed` | `add` / `delete` | 非公開 | パスベースのため任意パス読み込みの危険が残る |
| `/task` | `auth` | 非公開 | OAuth 認可。外部アカウント操作 |
| `/task` | `add` / `list` / `done` / `delete` | 非公開 | 状態を持たない読み書き系 |
| `/schedule` | `auth` / `refresh-token` | 非公開 | OAuth 認可・トークン更新 |
| `/bsky` | `reply` | 非公開 | 外部送信（投稿）。認証なし公開は危険 |
| `/chat` | — | 公開 | 非同期実行。SSE でレスポンス |
| `/cancel` | — | 公開 | `POST /api/v1/runs/{runId}/cancel` |
| `/runs` | — | 公開 | `GET /api/v1/runs/{runId}` |
| `/history` | — | 公開 | |
| `/models` | — | 非公開 | |
| `/model` | — | 非公開 | 設定変更を伴うので要確認 |
| `/search` | — | 公開 | |
| `/briefing` | — | 公開 | |
| `/feed` | — | サブコマンド単位で定義 | read / write / delete を個別判断 |
| `/reminder` | — | サブコマンド単位で定義 | read / write / delete を個別判断 |
| `/interest` | — | サブコマンド単位で定義 | read / write / delete を個別判断 |
| `/memory` | — | サブコマンド単位で定義 | read / write / delete を個別判断 |
| `/skill` | — | サブコマンド単位で定義 | read / write / delete を個別判断 |
| `/image` | — | 公開 | |
| `/summarize` | — | 公開 | |
| `/profile` | — | サブコマンド単位で定義 | read / write / delete を個別判断 |

### カレントプロジェクトの扱い

カレントプロジェクトはグローバル状態から引きはがし、**クライアントがチャット送信ごとに指定する**方式とする。

- `/project cd` は **Web API から削除**する。Web には「現在のプロジェクト」という状態がなく、`/project cd` を呼んでも後続リクエストに引き継ぐ状態が存在しないため。
- `projectId` は **リクエストボディ**で指定する。

```json
{
  "message": "このコードを調べて",
  "projectId": "550e8400-e29b-41d4-a716-446655440000",
  "sessionId": "optional"
}
```

- **任意のファイルシステムパスを受け付けない**。Web API が受け付けるのは**事前登録された projectId / projectName のみ**。
- サーバー側で `"rei"` → `ProjectRegistry` → 作業ディレクトリ と解決する。クライアントはパスを一切指定できない。
- `ProjectRegistry` に `resolveById` 的なメソッドを追加し、登録済み projectId から作業ディレクトリを解決する。

### Web スタック

- **Spring MVC**（`spring-boot-starter-web`）+ **`SseEmitter`** を採用する。
- 既存のサービス層（`TaskService` など）は同期ブロッキング実装のため、WebFlux ではなく Spring MVC を選定する。

### 認証

- **Spring Security** を採用する（`spring-boot-starter-security`）。
- `Authorization: Bearer <token>` 形式の API キーを **Spring Security filter chain** で検証する。
- 実装方法（`BearerTokenAuthenticationFilter` か `OncePerRequestFilter` か）は設計に委ねる。単一 static API key の認証なら `OncePerRequestFilter` の方が単純な場合がある。
- **API キー比較は constant-time comparison**（`MessageDigest.isEqual` 等）で行う。
- **API キーを絶対に request logging / exception message / AgentEvent / telemetry へ出さない**。
- API キー未設定時は **Web サーバーを起動しない**（`WebApplicationType.NONE`）。
- **API キー未設定の定義**: `REI_API_KEY` が未定義、空文字（`""`）、または trim 後空文字（`"   "`）の場合はすべて「未設定」として扱い、Web サーバーを起動しない。
- **Web API の有効化および API key の source of truth は環境変数 `REI_API_KEY` とする**。Spring 起動後は同値を `rei.api-key` として扱う。`application.yml` の `rei.api-key` からは設定しない（bootstrap 時点で読めないため）。

### 起動構成

- **シェル主 + Web 追加**。API キー設定時は組み込み Tomcat がバックグラウンドで動きつつ、シェルは従来通りメインスレッドで動く。
- 既存の CLI 利用を壊さない。
- **`WebApplicationType.NONE` の決定タイミングに注意**。`WebApplicationType` は ApplicationContext 作成**前**に決める必要がある。
  - `@ConfigurationProperties` で `rei.api-key` を読み取ってから `setWebApplicationType(NONE)` とはできない。
  - 起動時に `REI_API_KEY` 環境変数 → `SpringApplication` 作成 → SERVLET / NONE 判定 → `run()` という bootstrapping が必要。
- **Web API の有効化および API key の source of truth は環境変数 `REI_API_KEY` とする**。`application.yml` の `rei.api-key` は bootstrap 時点で読めないため、Phase 1 では環境変数限定とする。
  - `REI_API_KEY` が blank（未定義 / 空文字 / trim 後空文字）→ `WebApplicationType.NONE`
  - `REI_API_KEY` に値あり → `WebApplicationType.SERVLET`、Spring 起動後は同値を `rei.api-key`（`ApiKeyProperties`）として扱う。

### Web API DTO の分離

- Web API は CLI の内部型を直接 expose しない。**Web API 専用の request / response DTO を持つ**。
- `AgentEvent` をそのまま JSON 化する場合も、Java class 名や内部型変更が API breaking change にならないよう、外部 schema を固定する。

```text
AgentEvent
    ↓
Web API Event DTO
    ↓
JSON / SSE
```

### session / project の整合性

- **session 作成時の projectId に固定**する。途中で別 projectId を指定できない。
- 別プロジェクトで作業したい場合は新しい session を作成する。
- これにより history と Working Set が混ざるのを防ぐ。

### session / turn ID の定義

Web API の DTO が返す `sessionId` / `turnId` は、既存コードの ID 概念と対応させる。

- **`sessionId` は既存の `conversationId` に対応する**（論理会話の ID。Spring AI ChatMemory / `ConversationTurnStore` / `ConversationLogStore` のキー）。
  - Web API では session ごとに一意な conversationId を採番する。既存の `chat:main` 固定ではなく、`ConversationIds.chat(sessionId)` のように session 単位で生成する。
  - `sessionId` の存在判定（新規 / 継続 / `409` / `404`）は、この conversationId をキーに **`SessionRegistry`** で行う。`ConversationTurnStore` はターン一覧しか持たず、未登録 ID の `read()` は空一覧を返すため、存在判定には使えない。`SessionRegistry` は submit 受理時に sessionId → projectId を登録し、ターンの有無と独立して存在・所属 project を判定する。
- **`turnId` は既存の `runId` に対応する**（1 run = 1 turn）。
  - 既存コードに独立した `turnId` は存在しない（`ConversationTurnStore.Turn` は `runId` をターン識別子として使う）。
  - したがって `ChatResponse` / `RunResponse` の `turnId` は `runId` と**同一値**とする。
- **ID 採番の責務**は `ChatSubmitService`（application service）が担う。新規 session の `sessionId`（conversationId）と `runId` / `turnId` を採番し、`RunRegistry` と `ConversationTurnStore` に渡す。

### `POST /api/v1/chat` の sessionId semantics

`POST /api/v1/chat` の request で `sessionId` をどう渡すかを明確化する。

| ケース | 挙動 |
| --- | --- |
| **sessionId なし** | 新規 session を作成する |
| **sessionId あり** | 既存 session を継続する |
| **指定 session の projectId ≠ request.projectId** | `409 Conflict` を返す |
| **未知の sessionId** | `404 Not Found` を返す（黙って新規作成しない） |

- クライアントは `GET /api/v1/runs/{runId}` が返す `sessionId` を次のリクエストに渡すことで会話を継続できる。
- 未知の sessionId を `404` で返すことで、クライアントが古い sessionId を渡したときに「session が消えた」ことを明示的に検知できる。

### RunStatus の状態モデル

run の状態は `RunStatus` enum で管理し、以下の5状態と遷移を定義する。

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
- **`QUEUED` の run に対する cancel も受け付ける**。キューから除去して `CANCELLED` とし、`202` を返す。

### terminal event の定義

SSE ストリームの終了条件を明確化する。

- `agent.run.completed` / `agent.run.failed` / `agent.run.cancelled` を **terminal event** とする。
- `agent.run.cancelled` は `AgentEventType` に**新規追加**する（現状は `AGENT_RUN_STARTED` / `AGENT_RUN_COMPLETED` / `AGENT_RUN_FAILED` のみで、`AGENT_RUN_CANCELLED` は存在しない）。
- cancel された run の SSE も、`agent.run.cancelled` を受信した時点で `SseEmitter.complete()` によりストリームを閉じる。

### ReplayBuffer の sequence と run 単位保持の関係

- ReplayBuffer は **runId ごとにイベントを保持する**。
- ただし各イベントに付与される sequence は**プロセス全体で単調増加する global sequence** とする（run ごとに振り直さない）。
- これにより「run ごとに sequence を振り直す」誤解を防ぐ。

### ReplayBuffer の replay gap の判定

- replay gap は単純に `Last-Event-ID < oldestSequence` だけを見るのではなく、**その run に対して「クライアントが受信すべきイベントが失われているか」**で判定する。
- 実装上は各 run に `oldestRetainedSequence` / `latestSequence` / `evicted` のような metadata を持たせることで解決する。
- 要件としては「ReplayBuffer から当該 run の必要な履歴が失われている場合を replay gap とする」と定義する。

### ネットワーク公開範囲

- **`rei.web.bind-address=127.0.0.1` / `rei.web.port=8080` を正規設定**とし、それを Spring Boot の `server.address` / `server.port` に反映する形に統一する（二重管理を避ける）。
- LAN 公開したい場合のみ明示的に `rei.web.bind-address=0.0.0.0` 等を指定する。
- rei は Shell / File / Web / Process / MCP / Computer Use と非常に強い権限を持つため、API キーだけで Internet reachable にするのは避ける。

### Actuator 公開範囲

- `/actuator/health` **のみ**を unauthenticated endpoint として公開する。
- `/actuator/env` `/actuator/configprops` `/actuator/beans` などは外部公開しない。
- **unauthenticated `/actuator/health` は component detail / exception / 内部構成情報を返さない**（`management.endpoint.health.show-details=never` 相当）。

### SSE（イベント取りこぼし防止）

`POST /api/v1/chat` → `202 + runId` → クライアントが `GET /api/v1/runs/{runId}/events` で SSE 接続、という間にイベントが発生すると取りこぼす問題に対処する。

- **ReplayBuffer を新規実装**する（案X）。`AgentEventBus` の `lastSequence()` を `subscribe(fromSequence)` に拡張し、sequence ベースで replay 可能にする。

```text
AgentEvent
    sequence
       ↓
AgentEventStore / ReplayBuffer
       ↓
SSE subscribe(fromSequence)
```

- **ReplayBuffer は bounded とする**。run 単位で最大 10,000 events、run 完了後 30 分保持し、その後 purge する。無制限保持はしない。
- クライアントは `Last-Event-ID` ヘッダで再開位置を指定できる。**`Last-Event-ID: 123` は 123 は既受信なので 124 から**（指定 sequence より後のイベント）と厳密に定義する。
- 最低限、SSE 接続時に対象 run が既に完了していた場合は、その状態を送信して即 complete する。
- **ReplayBuffer が overflow して古いイベントが消えた場合（replay gap）**、`Last-Event-ID` で指定された sequence が最古の保持 sequence より古いときは、**`409 Conflict` で SSE 接続自体を拒否する**。`event: replay-gap` は v1 では採用しない。

### SSE（非同期境界）

`InMemoryAgentEventBus.publish()` は `synchronized` で listener を同期実行するため、SSE bridge の listener 内で `emitter.send()` すると遅いクライアントによって publisher thread（＝Agent 本体）がブロックされる。

- **bounded queue + SSE writer executor** を導入する（案P）。

```text
AgentEventBus
    ↓
bounded queue
    ↓
SSE writer executor
    ↓
SseEmitter
```

- queue 上限（例：1000 件）、slow consumer、client disconnect、send IOException の扱いを決める。
- **SSE client queue が上限に達した場合、イベントを黙って drop せず、接続を error completion で終了する**。クライアントは `Last-Event-ID` を使って再接続し、ReplayBuffer から再取得する。

### SSE（heartbeat）

- **`event: heartbeat` を 15〜30 秒周期で送信**する（案H1）。
- nginx / reverse proxy / load balancer / NAT の idle timeout を回避し、クライアント側が「接続が生きている」ことを明示的に検知できる。

### 並行実行

Web API 化すると複数クライアント・複数 run の並行実行が普通になる。

- **複数の run を並行して実行できる**ことを正式な要件とする。
- `AgentRunContext` は run ごとに独立する。
- run は projectId / sessionId / turnId / cancellation state を他 run と共有しない。
- **異なる projectId の run は並行実行できる。同一 projectId の run は FIFO で直列実行する**。`ConversationInputRouter.submit()` は projectId 単位に直列化している（同一プロジェクト内は直列）ことを要件として明示する。

---

## 要件

### 要件 1: Web スタックの導入

**ユーザーストーリー:** 開発者として、rei の機能を HTTP 経由で利用したい。そうすることで、外部クライアントから rei を操作できる。

#### 受け入れ基準

1. THE プロジェクト SHALL `spring-boot-starter-web` を依存に含める
2. THE プロジェクト SHALL `spring-boot-starter-actuator` を依存に含める
3. THE プロジェクト SHALL Spring MVC を Web スタックとして使用する
4. THE プロジェクト SHALL `SseEmitter` を用いて SSE を実装できる構成とする

---

### 要件 2: API キー認証

**ユーザーストーリー:** 運用者として、Web API を認証で保護したい。そうすることで、許可されたクライアントだけが rei を操作できる。

#### 受け入れ基準

1. THE プロジェクト SHALL `spring-boot-starter-security` を依存に含める
2. THE プロジェクト SHALL `Authorization: Bearer <token>` 形式の API キーを Spring Security filter chain で検証する
3. WHEN リクエストの API キーが設定値と一致しないとき、THE サーバー SHALL `401` を返す
4. THE プロジェクト SHALL `rei.api-key` プロパティ（環境変数 `REI_API_KEY`）で API キーを設定する
5. WHEN API キーが未設定のとき、THE アプリケーション SHALL Web サーバーを起動しない（`WebApplicationType.NONE`）
6. THE アプリケーション SHALL `/actuator/health` のみを認証不要（`permitAll`）とし、その他の Actuator endpoint を外部公開しない
7. THE サーバー SHALL API キー比較を constant-time comparison（`MessageDigest.isEqual` 等）で行う
8. THE サーバー SHALL API キーを request logging / exception message / AgentEvent / telemetry へ出力しない
9. WHEN `REI_API_KEY` が未定義、空文字（`""`）、または trim 後空文字（`"   "`）のとき、THE アプリケーション SHALL API キー未設定として扱い、Web サーバーを起動しない
10. THE アプリケーション SHALL `management.endpoints.web.exposure.include=health` と `management.endpoint.health.show-details=never` 相当を設定する（`/actuator/health` のみを公開し、component detail / exception / 内部構成情報を返さない）

---

### 要件 3: 起動構成（シェル主 + Web 追加）

**ユーザーストーリー:** 利用者として、従来通り CLI で rei を使い続けたい。そうすることで、Web API 追加後も既存の操作を維持できる。

#### 受け入れ基準

1. WHEN API キーが設定されているとき、THE アプリケーション SHALL 組み込み Tomcat をバックグラウンドで起動しつつ、シェルをメインスレッドで起動する
2. WHEN API キーが未設定のとき、THE アプリケーション SHALL シェルのみを起動する（Web サーバーを起動しない）
3. THE アプリケーション SHALL `WebApplicationType` を ApplicationContext 作成前に決定する（`REI_API_KEY` 環境変数から SERVLET / NONE を判定する bootstrapping を行う）

---

### 要件 4: Run API 中心のエンドポイント

**ユーザーストーリー:** クライアントとして、チャットを開始して結果をリアルタイムに受け取りたい。そうすることで、チャットらしい体験を得られる。

#### 受け入れ基準

1. THE サーバー SHALL `POST /api/v1/chat` を提供し、`ConversationInputRouter.submit()` で非同期実行を開始して `202 Accepted` と `runId` / `sessionId` / `turnId` を返す（`ChatSubmitService` が採番した `runId` を含む `AgentRunContext` をそのまま渡し、内部で再採番しない）
2. THE サーバー SHALL `POST /api/v1/chat` の `202 Accepted` レスポンスに `Location: /api/v1/runs/{runId}` ヘッダを返す（REST API として扱いやすくするため）
3. THE サーバー SHALL `GET /api/v1/runs/{runId}` を提供する
4. THE サーバー SHALL `GET /api/v1/runs/{runId}/events` で SSE 接続を提供する
5. THE サーバー SHALL `POST /api/v1/runs/{runId}/cancel` を提供する
6. THE サーバー SHALL `/cancel` を `runId` 必須とする（CLI の「現在動いているものを cancel」は Web では成立しない）

---

### 要件 5: SSE の sequence/replay（イベント取りこぼし防止）

**ユーザーストーリー:** クライアントとして、`POST /api/v1/chat` と SSE 接続の間に発生したイベントも取りこぼさずに受け取りたい。そうすることで、実行開始直後のイベントも確実に受信できる。

#### 受け入れ基準

1. THE サーバー SHALL `AgentEventBus` のイベントに sequence を割り当てる（**プロセス全体のグローバル sequence**、run 単位ではない）
2. THE サーバー SHALL ReplayBuffer を実装し、`subscribe(fromSequence)` で過去イベントを replay できるようにする
3. THE ReplayBuffer SHALL bounded とする（run 単位で最大 10,000 events、run 完了後 30 分保持し、その後 purge する）
4. WHEN クライアントが `Last-Event-ID` ヘッダを送信したとき、THE サーバー SHALL 指定 sequence **より後**のイベントを replay する（`Last-Event-ID: 123` は 124 から）
5. WHEN SSE 接続時に対象 run が既に完了していた場合、THE サーバー SHALL その状態を送信して即 complete する
6. THE サーバー SHALL `AgentEvent.runId()` が対象の `runId` と一致するイベントのみをストリーミングする
7. WHEN `agent.run.completed` / `agent.run.failed` / `agent.run.cancelled` を受信したとき、THE サーバー SHALL `SseEmitter.complete()` でストリームを閉じる（これらを terminal event とする）
8. WHEN SSE client queue が上限に達したとき、THE サーバー SHALL イベントを黙って drop せず、接続を error completion で終了する（クライアントは `Last-Event-ID` で再接続し ReplayBuffer から再取得する）
9. WHEN ReplayBuffer から当該 run のクライアントが受信すべき履歴が失われているとき（replay gap）、THE サーバー SHALL `409 Conflict` で SSE 接続を拒否する
10. THE ReplayBuffer SHALL runId ごとにイベントを保持する。ただし各イベントに付与される sequence はプロセス全体で単調増加する global sequence とする（run ごとに振り直さない）
11. THE サーバー SHALL replay gap を「ReplayBuffer から当該 run の必要な履歴が失われている場合」と定義する（単純な `Last-Event-ID < oldestSequence` ではなく、run 単位の metadata で判定する）
12. WHEN `Last-Event-ID` が terminal event の sequence と同値、または未来の sequence の場合（replay 対象が空）、THE サーバー SHALL イベントを再送せず即 `complete()` する（イベントが来ないのに接続が残らない）

---

### 要件 6: SSE の非同期境界（EventBus 非ブロック）

**ユーザーストーリー:** 運用者として、遅い SSE クライアントがいても Agent 本体の実行がブロックされないことを保証したい。そうすることで、他のクライアントや実行に影響を与えない。

#### 受け入れ基準

1. THE サーバー SHALL `AgentEventBus` と `SseEmitter` の間に bounded queue を設ける
2. THE サーバー SHALL SSE 書き込みを専用の executor で行う
3. THE サーバー SHALL bounded queue の上限を設定する
4. THE サーバー SHALL slow consumer / client disconnect / send IOException の扱いを定める
5. THE サーバー SHALL `onCompletion` / `onTimeout` / `onError` で Listener を必ず `unsubscribe` する（リーク防止）

---

### 要件 7: SSE heartbeat

**ユーザーストーリー:** クライアントとして、長時間の実行中も SSE 接続が維持されることを保証したい。そうすることで、プロキシや NAT の idle timeout で切断されない。

#### 受け入れ基準

1. THE サーバー SHALL SSE 接続維持のため `event: heartbeat` を 15〜30 秒周期で送信する

---

### 要件 8: プロジェクト指定（登録済み projectId のみ）

**ユーザーストーリー:** クライアントとして、リクエストごとに作業対象プロジェクトを指定したい。そうすることで、グローバル状態に依存せず安全に操作できる。

#### 受け入れ基準

1. THE Web API SHALL カレントプロジェクトをグローバル状態（`ProjectService.currentService` の `AtomicReference<Path> currentProject`）に依存しない
2. THE チャット送信 API SHALL `projectId` をリクエストボディで受け取る
3. THE Web API SHALL クライアントから任意のファイルシステムパスを受け付けない
4. THE Web API SHALL 登録済み projectId から作業ディレクトリを解決する
5. THE Web API SHALL `/project cd` を公開しない（Web API から削除する）
6. THE `ProjectRegistry` SHALL 登録済み projectId から作業ディレクトリを解決するメソッド（`resolveById` 等）を提供する
7. THE Web API SHALL 認証付き `GET /api/v1/projects` で登録済みプロジェクトを `200 OK` の JSON 配列として返す。各要素は `id`（登録済み UUID）と `name` のみを持ち、ファイルシステムパスを含めない
8. WHEN プロジェクトが未登録のとき、THE 一覧 API SHALL `200 OK` と `[]` を返し、レジストリファイルを作成しない
9. THE 一覧 API SHALL 登録順で最新の登録内容を返し、プロジェクトの登録・削除・カレントプロジェクト変更を行わない。取得した `id` は `POST /api/v1/chat` の `projectId` に指定できる
10. WHEN API キーがない、または不正なとき、THE 一覧 API SHALL `401 Unauthorized` を返す

---

### 要件 9: 並行実行

**ユーザーストーリー:** 運用者として、複数クライアントから複数の run を並行して実行したい。そうすることで、Web API として複数ユーザーを同時に扱える。

#### 受け入れ基準

1. THE Web API SHALL 複数の run を並行して実行できる
2. THE `AgentRunContext` SHALL run ごとに独立する
3. THE run SHALL projectId / sessionId / turnId / cancellation state を他 run と共有しない
4. THE サーバー SHALL 異なる projectId の run を並行実行できる
5. THE サーバー SHALL 同一 projectId の run を FIFO で直列実行する（`ProjectRunQueue` が projectId 単位に直列化する。`ConversationInputRouter.submit()` は内部で runId を再採番せず、`ChatSubmitService` が採番した `AgentRunContext` をそのまま受け取る）

---

### 要件 10: ネットワーク公開範囲

**ユーザーストーリー:** 運用者として、Web API をローカルホストのみに公開したい。そうすることで、API キーだけで Internet に公開されるリスクを避ける。

#### 受け入れ基準

1. THE サーバー SHALL `rei.web.bind-address=127.0.0.1` / `rei.web.port=8080` を正規設定とし、Spring Boot の `server.address` / `server.port` に反映する
2. THE サーバー SHALL LAN 公開する場合のみ明示的に `rei.web.bind-address=0.0.0.0` 等を指定できる

---

### 要件 11: 公開コマンドの粒度（deny by default）

**ユーザーストーリー:** 運用者として、公開コマンドを細かく制御したい。そうすることで、後から危険な操作が追加されても自動的に公開されない。

#### 受け入れ基準

1. THE Web API SHALL ShellCommand をそのまま HTTP 化しない
2. THE Web API SHALL Web API endpoint → application service の構造を明示的に作る
3. THE Web API SHALL サブコマンドを持つコマンド（`/memory` `/profile` `/interest` `/feed` `/reminder` など）の `read` / `write` / `delete` を個別に判断する
4. THE Web API SHALL deny by default とする
5. THE Web API SHALL Phase 1 では `GET /api/v1/projects` / `POST /api/v1/chat` / `GET /api/v1/runs/{runId}` / `GET /api/v1/runs/{runId}/events` / `POST /api/v1/runs/{runId}/cancel` / `GET /actuator/health` のみを実装対象とする
6. THE Web API SHALL `/history` `/search` `/briefing` `/feed` `/reminder` `/interest` `/memory` `/skill` `/image` `/summarize` `/profile` を後続 Phase で追加する（Phase 1 の実装対象外）

---

### 要件 12: Run API の状態モデルと HTTP status

**ユーザーストーリー:** クライアントとして、run の状態と HTTP status を一貫して扱いたい。そうすることで、異常系も含めて API クライアントを実装できる。

#### 受け入れ基準

1. THE サーバー SHALL `RunStatus` enum を定義する（`QUEUED` / `RUNNING` / `COMPLETED` / `FAILED` / `CANCELLED` の5状態）
2. THE サーバー SHALL `GET /api/v1/runs/{runId}` で `status` / `runId` / `sessionId` / `turnId` / `projectId` / `startedAt` / `completedAt` / `failure` を返す
3. WHEN 存在しない runId へのリクエスト（`GET` / `events` / `cancel`）を受けたとき、THE サーバー SHALL `404 Not Found` を返す
4. WHEN 完了後 purge 済みの runId へのリクエストを受けたとき、THE サーバー SHALL `404 Not Found` を返す（`410 Gone` は採用しない。tombstone を保持しないため「存在したが purge 済み」と「最初から存在しない」を区別しない）
5. WHEN `POST /api/v1/runs/{runId}/cancel` を受けたとき、THE サーバー SHALL 冪等に現在状態を返す（unknown run → `404` / running → `202` / already terminal → `200`）
6. WHEN `POST /api/v1/runs/{runId}/cancel` を `QUEUED` 状態の run に対して受けたとき、THE サーバー SHALL キューから除去して `CANCELLED` とし、`202` を返す
7. THE サーバー SHALL `COMPLETED` / `FAILED` / `CANCELLED` を terminal 状態として扱う
8. WHEN `POST /api/v1/runs/{runId}/cancel` を `RUNNING` 状態の run に対して受けたとき、THE サーバー SHALL Registry の状態確定に加えて実際の実行中の runner を停止する（runId ごとの停止ハンドルを呼ぶ）
9. WHEN dequeue 後〜実行開始前に cancel されたとき、THE サーバー SHALL runner を開始せず `CANCELLED` として確定する（開始前キャンセル要求を保持する）
10. THE サーバー SHALL 同一 project の次の run を、実際の runner 終了まで開始しない（`CANCELLED` で SSE が閉じた後も実行が継続しない）

---

### 要件 13: Web API DTO の分離

**ユーザーストーリー:** 開発者として、内部型の変更が API breaking change にならないことを保証したい。そうすることで、Web API の外部 schema を安定して維持できる。

#### 受け入れ基準

1. THE Web API SHALL CLI の内部型を直接 expose しない
2. THE Web API SHALL Web API 専用の request / response DTO を持つ
3. THE Web API SHALL `AgentEvent` を Web API Event DTO に変換してから JSON / SSE で出力する
4. THE Web API SHALL 外部 schema を固定し、Java class 名や内部型変更が API breaking change にならないようにする

---

### 要件 14: session / project の整合性

**ユーザーストーリー:** クライアントとして、session と project の関係を明確にしたい。そうすることで、history と Working Set が混ざるのを防げる。

#### 受け入れ基準

1. THE Web API SHALL session 作成時の projectId に固定する
2. THE Web API SHALL 途中で別 projectId を指定できないようにする
3. THE Web API SHALL 別プロジェクトで作業する場合は新しい session を作成する
4. WHEN `POST /api/v1/chat` の request に `sessionId` が含まれないとき、THE サーバー SHALL 新規 session を作成する
5. WHEN `POST /api/v1/chat` の request に `sessionId` が含まれるとき、THE サーバー SHALL 既存 session を継続する
6. WHEN 指定された session の projectId が request の projectId と一致しないとき、THE サーバー SHALL `409 Conflict` を返す
7. WHEN 未知の sessionId が指定されたとき、THE サーバー SHALL `404 Not Found` を返す（黙って新規作成しない）
8. THE サーバー SHALL `sessionId` を既存の `conversationId` に対応させ、session ごとに一意な conversationId を採番する（既存の `chat:main` 固定ではなく session 単位で生成する）
9. THE サーバー SHALL `turnId` を既存の `runId` に対応させ、`ChatResponse` / `RunResponse` の `turnId` を `runId` と同一値とする（既存コードに独立した `turnId` は存在しない）
10. THE サーバー SHALL ID 採番の責務を `ChatSubmitService`（application service）が担い、新規 session の `sessionId`（conversationId）と `runId` / `turnId` を採番して `RunRegistry` と `ConversationTurnStore` に渡す
11. THE サーバー SHALL session の存在判定をターンの有無と独立に行う（`SessionRegistry` 等で sessionId → projectId を submit 受理時に登録し、QUEUED のままの session も存在判定できる）
12. THE サーバー SHALL 採番した `runId` を含む `AgentRunContext` を `ProjectRunQueue` と runner にそのまま渡し、内部で再採番しない（レスポンス / `RunRegistry` / `ConversationTurnStore` / `AgentEvent` の runId を一致させる）

---

### 要件 15: 公開コマンド表の粒度（サブコマンド単位）

**ユーザーストーリー:** 運用者として、公開コマンドの粒度をサブコマンド単位で制御したい。そうすることで、後から危険な操作が追加されても自動的に公開されない。

#### 受け入れ基準

1. THE Web API SHALL 公開コマンド表をサブコマンド単位で定義する（`/feed` `/memory` `/profile` `/interest` `/reminder` `/skill` は `read` / `write` / `delete` を個別判断）
2. THE Web API SHALL 表の「公開」表記をサブコマンド単位に修正し、Codex が「全部公開していい」と解釈しないようにする
