# 実装タスク: Web API 機能（Phase 1）

## Phase 1 拡張: Session History（2026-09-17）

- [x] Unicode code point 単位の80文字 title policy と境界テストを Red → Green で追加。
- [x] SessionRepository port と atomic JSON adapter、再初期化・保存失敗・enqueue 失敗復元をテスト。
- [x] ChatSubmitService に受理時 create/touch を接続し、project 固定・未知 ID・再起動後の継続を確認。
- [x] 共通 SessionQueryService、安定順序、project filter、scope 付き cursor、limit 共通 policy を追加。
- [x] 認証付き Session 一覧・詳細・Turns の専用 DTO と HTTP endpoint を追加。
- [x] 既存 ConversationTurnStore に開始時刻と最終応答を保存し、時系列ページングと旧形式読込を確認。
- [x] `/history` 一覧と `/history show <sessionId>` を共通 query に接続。旧 list/search/show オプションの回帰テストを実施。
- [x] 実 HTTP 再起動テスト、同時更新、ページ途中の新規挿入、認証なし／不正キーのテストを実施。
- [x] README・要件・設計・[利用仕様](../../../docs/session-history.md)を更新。旧データの backfill 非実施と保存／ページングの制約を明記。

各機能の未実装クラス・メソッド、HTTP 404 による Red を確認してから実装し、成功状態ごとにコミットした。追加35件、全体1,803件（失敗0、エラー0、既存skip2）で成功。専用出力先で Spring Boot package も成功。コマンド・環境上の制約は [実装報告](../../../docs/session-history-implementation.md) を参照。

## 進め方

このタスクは t_wada の TDD に則り、原則として次のサイクルで進める。

1. **Red**: 期待する振る舞いを表す失敗するテストを先に書く。コンパイルエラーも Red として扱う。
2. **Green**: テストを通すための最小実装を行う。きれいさより動くことを優先する。
3. **Refactor**: テストが通ったまま、重複・命名・責務分割を整える。

実装は小さな単位で進め、各 Green または Refactor の区切りでテストを確認する。
コミットする場合は、関連するテストと実装を同じコミットに含める。

> テストを書く前に実装を書かない。実装を書く前にテストを書く。

## 実装順序（設計書に基づく）

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

## 前提

- ビルド: `JAVA_HOME=C:\Java\jdk-25` を設定して `mvnw.cmd` を使う（`mvn` は PATH にない）。
- テスト実行: `./mvnw.cmd test "-Dtest=<テスト名>"` で対象テストのみ実行できる。
- 既存の CLI 利用を壊さない（Shell UI と Web UI は application/core に並列にぶら下がる）。
- カレントプロジェクトはグローバル状態から引きはがし、クライアントがチャット送信ごとに `projectId` を指定する。
- Phase 1 の公開対象は `GET /api/v1/sessions`、`GET /api/v1/sessions/{sessionId}`、`GET /api/v1/sessions/{sessionId}/turns`、`GET /api/v1/projects`、`POST /api/v1/chat`、`GET /api/v1/runs/{runId}`、`GET /api/v1/runs/{runId}/events`、`POST /api/v1/runs/{runId}/cancel`、`GET /actuator/health` のみ。後続 Phase のコマンドや非公開コマンドは追加しない。
- 以下の Phase 0〜9 は、この Phase 1 機能を実装する作業段階を表す。
- 先行タスクでは後続コンポーネントのインターフェースとテストダブルを使う。4.3 の queue / runner 連携は 5.2〜5.4、3.3 の同時 purge は 8.7、6.4 の SSE 変換は 7.2 / 8.6 で実物を接続して検証する。
- サービス層は状態・戻り値・例外、Controller 層は HTTP status / header / JSON を検証する。並行処理は latch 等、期限・heartbeat は制御可能な時計や scheduler を使い、実時間の長い待機に依存しない。

---

## タスク一覧

### Phase 0: 依存・基盤

- [x] 0.1 `pom.xml` に Web / Security / Actuator 依存を追加する
  - Red: MVC / Security / Actuator を使用する最小の起動テストを追加し、不足する依存による失敗を確認する。依存名の文字列を検査するだけのテストは作らない。
  - Green: `pom.xml` に 3 つの依存を追加する。
  - Refactor: 既存の依存と重複しないことを確認する。
  - _Requirements: 1.1, 1.2, 1.3, 1.4, 2.1_

### Phase 1: Web 起動（bootstrapping）

- [x] 1.1 `ApiKeyProperties` を追加する
  - Red: 環境変数 `REI_API_KEY` と同値が `ApiKeyProperties` に渡ることを確認する。設定ファイルやコマンドラインの `rei.api-key` が環境変数の値を上書きせず、環境変数が blank の場合にも Web を有効化しないことを検証する。
  - Green: `ApiKeyProperties` と登録処理を実装し、起動判定と認証に使うキーの source of truth を `REI_API_KEY` に統一する。`application.yaml` にキーを定義しない。
  - Refactor: プロパティ名と環境変数 `REI_API_KEY` の対応を整理する。
  - _Requirements: 2.4, 2.9_

- [x] 1.2 `WebApplication` の bootstrapping（`WebApplicationType` 判定）を実装する
  - Red: `REI_API_KEY` が blank（未定義 / 空文字 / trim 後空文字）なら `WebApplicationType.NONE`、値ありなら `SERVLET` を返すテストを追加する。
  - Green: `WebApplication` の判定ロジックを実装する。
  - Refactor: 判定を純粋関数に切り出してテストしやすくする。
  - _Requirements: 2.5, 2.9, 3.3_

- [x] 1.3 `ReiApplication.main()` に bootstrapping を組み込む
  - Red: `REI_API_KEY` 未設定時に `WebApplicationType.NONE` で起動することを確認するテストを追加する。
  - Green: `main()` で `REI_API_KEY` を読み、`SpringApplication.setWebApplicationType()` を呼ぶ。
  - Red / Green: Context 作成前に判定され、blank の全ケースでシェルのみ起動し、値ありで Tomcat と従来のシェルが共存することを確認する。Web / Security の servlet 専用 Bean は NONE 起動を妨げない条件で登録する。
  - Refactor: 既存の CLI 起動フローを壊さないことを確認する。
  - _Requirements: 3.1, 3.2, 3.3_

- [x] 1.4 ネットワーク設定を実装する
  - Red: 既定値が `127.0.0.1:8080` となり、`rei.web.bind-address` / `rei.web.port` の明示設定が `server.address` / `server.port` に反映されるテストを追加する。
  - Green: 正規設定を `rei.web.*` に統一し、Spring Boot のサーバー設定へ反映する。
  - Refactor: 明示した場合のみ `0.0.0.0` 等へ公開されることを確認する。
  - _Requirements: 10.1, 10.2_

### Phase 2: Security

- [x] 2.1 `ApiKeyAuthenticationFilter` を実装する
  - Red: `Authorization: Bearer <token>` が正しい場合に認証成功、誤りなら `401` を返すテストを追加する。
  - Red: ヘッダなし、Bearer 値なし、不正な認証形式でも `401` になることを確認する。
  - 検証: 比較は `MessageDigest.isEqual` 等の使用をコードレビューで確認する。実測時間のテストで constant-time を保証しようとしない。
  - Green: `OncePerRequestFilter` を実装する。
  - Red / Green: テスト用キーが request logging / 例外メッセージ / AgentEvent / telemetry に出ないことを検証し、必要な秘匿処理を実装する。
  - _Requirements: 2.2, 2.3, 2.7, 2.8_

- [x] 2.2 `SecurityConfig` を実装する
  - Red: `/actuator/health` のみ `permitAll`、その他は認証必須、`STATELESS` + `csrf.disable()` を確認するテストを追加する。
  - Green: `SecurityFilterChain` を実装する。
  - Red / Green: filter chain 経由で認証済み GET と POST が成功し、CSRF による `403` やログイン画面への redirect が起きず、認証用 HTTP session を作らないことを検証する。SSE の非同期 dispatch も検証する。
  - Refactor: `/api/**` の認証ルールを整理する。
  - _Requirements: 2.6, 2.10_

- [x] 2.3 Actuator 公開範囲を設定する
  - Red: `management.endpoints.web.exposure.include=health` と `show-details=never` を確認するテストを追加する。
  - Green: `application.yaml` に設定を追加する。
  - Red / Green: 実際の `/actuator/health` の応答に component detail / exception / 内部構成が含まれず、認証済みでも `/actuator/env` / `configprops` / `beans` が公開されないことを検証する。
  - Refactor: 設定の重複を避ける。
  - _Requirements: 2.6, 2.10_

### Phase 3: RunRegistry / 状態モデル

- [x] 3.1 `RunStatus` enum を追加する
  - Red: `QUEUED` / `RUNNING` / `COMPLETED` / `FAILED` / `CANCELLED` の5状態と terminal 判定を確認するテストを追加する。
  - Green: `RunStatus` enum を実装する。
  - Refactor: terminal 判定メソッドの命名を整理する。
  - _Requirements: 12.1, 12.7_

- [x] 3.2 `RunRegistry` を実装する（状態遷移）
  - Red: `register(QUEUED)` → `RUNNING` → terminal の遷移を確認するテストを追加する。
  - Red: terminal 状態から他状態への遷移が拒否される（atomic / immutable）テストを追加する。
  - Red: cancel と terminal event の競合で `CANCELLED → COMPLETED` にならないテストを追加する。
  - Green: `RunRegistry` を実装する（`synchronized` による状態と metadata の atomic transition）。
  - Refactor: 状態遷移の invariant を明示する。
  - _Requirements: 12.1, 12.2, 12.7_

- [x] 3.3 `RunRegistry` の retention / purge を実装する
  - Red: terminal から 30 分で purge され、QUEUED / RUNNING は経過時間だけで削除されないテストを追加する。保持中の terminal run への再アクセスで期限を延長しない。
  - Green: retention / purge ロジックを実装する。
  - Refactor: `ReplayBuffer` と同一 retention に統一する。
  - _Requirements: 12.4_

- [x] 3.4 `GET /api/v1/runs/{runId}` を実装する
  - Red: `runId` / `status` / `sessionId` / `turnId` / `projectId` / `startedAt` / `completedAt` / `failure` を返すテストを追加する。`turnId = runId`、QUEUED の `startedAt = null`、非 terminal の `completedAt = null`、日時の ISO-8601 形式、FAILED のみ `failure = {type, message}`（他は null）を検証する。
  - Red: 存在しない / purge 済み runId → `404` のテストを追加する。
  - Green: `RunService` の状態取得と `RunController`、`RunResponse` DTO を実装する。Controller は application service を呼び出す。
  - Refactor: DTO と内部型の分離を確認する。
  - _Requirements: 4.3, 12.2, 12.3, 12.4, 13.1, 13.2_

### Phase 4: Chat submit

- [x] 4.1 `SessionRegistry` を実装する
  - Red: `sessionId`（conversationId）→ `projectId` の登録・存在判定・所属 project 取得を確認するテストを追加する。
  - Red: ターン未作成（QUEUED のまま）の session も存在判定できるテストを追加する。
  - Green: `SessionRegistry` を実装する。
  - Refactor: ターンの有無と独立した存在判定を明示する。
  - _Requirements: 14.11, 14.8_

- [x] 4.2 `ProjectRegistry.resolveById()` を追加する
  - Red: 登録済み projectId から作業ディレクトリを解決するテストを追加する。
  - Green: `resolveById(String id)` を実装する。
  - Red / Green: 未登録 ID とパス文字列を登録済み ID として解決できず、任意パスへのフォールバックやプロジェクトの自動登録を行わないことを検証する。
  - Refactor: 未知の projectId の扱いを整理する。
  - _Requirements: 8.4, 8.6_

- [x] 4.3 `ChatSubmitService` を実装する（session / project 整合性 + ID 採番）
  - Red: `sessionId` なしで新規 session と QUEUED run が登録され、採番した ID と解決済み project を queue に渡して runner 完了前に戻るテストを追加する。
  - Red: `sessionId` あり → 既存 session 継続のテストを追加する。
  - Red: session の projectId 不一致、未知の sessionId / projectId を区別する例外を返し、拒否した入力で session / run の登録や enqueue を行わないテストを追加する。HTTP への変換は 4.4 で検証する。
  - Red: 採番した `runId` が戻り値 / `RunRegistry` / queue に渡す `AgentRunContext` で一致し、`turnId = runId` となるテストを追加する。実際の Store / AgentEvent との一致は 5.4 で検証する。
  - Green: `ChatSubmitService` を実装する（`sessionId` = conversationId 採番、`turnId` = runId、`RunRegistry.register(QUEUED)`、`ProjectRunQueue.enqueue`、`SessionRegistry` 登録）。
  - Refactor: ID 採番責務を `ChatSubmitService` に集約する。
  - _Requirements: 4.1, 4.2, 14.4, 14.5, 14.6, 14.7, 14.8, 14.9, 14.10, 14.12_

- [x] 4.4 `ChatController` を実装する
  - Red: `POST /api/v1/chat` が `202` + `Location` + `ChatResponse` を返すテストを追加する。
  - Green: `ChatController` と `ChatRequest` / `ChatResponse` DTO を実装する。
  - Red / Green: 必須 `message` / `projectId` の欠落を拒否する入力検証と、未知の project / session → `404`、所属 project 不一致 → `409` の例外マッピングを実装する。新規・継続とも応答の ID と Location が一致し、任意の作業パスを指定する API を持たないことを確認する。
  - Refactor: DTO と内部型の分離を確認する。
  - _Requirements: 4.1, 4.2, 8.2, 8.3, 13.1, 13.2, 14.4, 14.5, 14.6, 14.7_

- [x] 4.5 `SessionRegistry` の寿命を実装する
  - 以下は初期実装の記録。Session History 拡張後は Registry の失効を runtime cache に限定し、Chat の継続判定は永続 SessionRepository が行う。
  - Red: run の purge と session の失効が独立し、有効な session は run purge 後や QUEUED cancel 後にも継続できることを検証する。最終アクセスによる期限更新と、失効後の継続が `404` になることも確認する。
  - Green: 設計書の session 最終アクセス基準の保持ポリシーを実装する。保持時間（設計例は 30 分）とアクセス更新条件を明示する。
  - Refactor: sessionId は conversationId として一貫して扱い、`chat:main` 固定や二重の接頭辞付与を避ける。
  - _Requirements: 14.7, 14.8, 14.11; Design: session の存在判定と所属 project（SessionRegistry）_

### Phase 5: Project FIFO

- [x] 5.1 `ProjectRunQueue` を実装する（enqueue / cancelQueued）
  - Red: 同一 projectId の run を FIFO 直列で実行するテストを追加する。
  - Red: 異なる projectId の run を並行実行するテストを追加する。
  - Red: `cancelQueued(runId)` で QUEUED の run をキューから除去できるテストを追加する。
  - Green: `ProjectRunQueue` を実装する。
  - Refactor: AGENT run の FIFO に限定し、`submitBackground` / `executeAuxiliary` は `ConversationInputRouter` が管理することを明示する。
  - _Requirements: 9.4, 9.5, 12.6_

- [x] 5.2 `ConversationInputRouter` の FIFO 責務を `ProjectRunQueue` へ移す
  - Red: `submit()` が内部で runId を再採番せず、`ChatSubmitService` が採番した `AgentRunContext` をそのまま受け取るテストを追加する。
  - Green: `ConversationInputRouter` を改修する（runId 再採番を廃止、`ProjectRunQueue` へ委譲）。
  - Red / Green: Web submit → Router / queue → runner が一度だけ enqueue・実行されることを確認する。CLI の AGENT run も同じ project queue を使い、非 AGENT の `submitBackground` と `executeAuxiliary` は queue に入らないことをテストする。
  - Refactor: 既存の CLI 経路（`submit(Path, conversation, prompt)`）を壊さないことを確認する。
  - _Requirements: 9.5, 14.12_

- [x] 5.3 runner のライフサイクルと異常終了回収を接続する
  - Red: 実際の dequeue で RUNNING、terminal event で COMPLETED / FAILED へ遷移し、時刻と failure が更新されることを確認する。
  - Red: terminal event 未発行のまま正常復帰または例外で終了した runner を executor 境界で FAILED に確定し、`agent.run.failed` を発行して次の run へ進めるテストを追加する。
  - Green: queue の実行境界で戻り値・例外と終端状態を回収する。既に terminal の run は上書きせず、terminal event を重複発行しない。
  - Refactor: Registry 更新とイベント処理の責務を明示し、RUNNING のまま残る経路をなくす。
  - _Requirements: 12.1, 12.2, 12.7; Design: 異常終了の回収（terminal event 未発行の run）_

- [x] 5.4 project / session / run の分離を実際の実行経路で検証する
  - Red: CLI の current project を変更しても、受理済み Web run の作業ディレクトリと projectId が変わらず、Web submit も CLI の current project を書き換えないことを確認する。
  - Red: 同じ project の異なる session の ChatMemory / history / Working Set が混ざらず、同じ session の継続時だけ会話を引き継ぐことを確認する。
  - Red: 異なる project の並行 run で `AgentRunContext` と cancellation state が独立し、レスポンス / Registry / ConversationTurnStore / AgentEvent の runId・sessionId・projectId が一致することを確認する（`turnId = runId`）。
  - Green: 解決済み project と session 固有の conversationId を runner・下位サービスへ伝搬し、Web 実行中のグローバル current project 参照を解消する。
  - Refactor: 同じ session / project の ID 値を再利用することと、run ごとの可変状態を共有することを区別する。
  - _Requirements: 8.1, 8.2, 8.3, 8.4, 9.1, 9.2, 9.3, 14.1, 14.2, 14.3, 14.8, 14.9, 14.10, 14.12_

### Phase 6: Cancel

- [x] 6.1 `AgentEventType.AGENT_RUN_CANCELLED` を追加する
  - Red: `agent.run.cancelled` の enum 値が存在するテストを追加する。
  - Green: `AgentEventType` に `AGENT_RUN_CANCELLED("agent.run.cancelled")` を追加する。
  - Refactor: 既存の enum 値と整合することを確認する。
  - _Requirements: 5.7, 12.7_

- [x] 6.2 `AgentEventFactory.runCancelled()` を追加する
  - Red: `runCancelled(runId, error)` が `agent.run.cancelled` イベントを生成するテストを追加する。
  - Green: `AgentEventFactory.runCancelled()` を実装する。
  - Refactor: 既存の `runFailed` と整合することを確認する。
  - _Requirements: 5.7, 12.7_

- [x] 6.3 `CommandCancellationService.cancelRun(runId)` を追加する
  - Red: `cancelRun(runId)` が実行中の runner を停止する（`cancellationRequested` を立て、children 実行 / disposable dispose / thread interrupt）テストを追加する。
  - Red: `begin()` 前に `cancelRun(runId)` が呼ばれても要求が消えない（pending cancellation）テストを追加する。
  - Green: `cancelRun(runId)` と pending cancellation のデータ構造を実装する。
  - Refactor: 既存 `onCancel(runId, child)` は子コールバック登録のままにする。
  - _Requirements: 12.8, 12.9_

- [x] 6.4 `RunService.cancel()` を実装する
  - Red: `RUNNING` → `202`、`QUEUED` → `202`（キューから除去して `CANCELLED`）、既に terminal → `200`、存在しない runId → `404` のテストを追加する。
  - Red: cancel 成功時のみ `agent.run.cancelled` を発行する（atomic transition 成功時）テストを追加する。
  - Red: cancel と完了・失敗が競合しても、勝った terminal 状態を保持し、cancel 遷移に失敗した場合は現在状態を返して cancelled event を発行しないことを確認する。
  - Green: `RunService.cancel()` を実装する。RUNNING は atomic な CANCELLED 確定 → `CommandCancellationService.cancelRun(runId)` → `AgentEventFactory.runCancelled()` によるイベント発行の順で処理する。QUEUED はキューから除去する。
  - Refactor: Registry の terminal 状態を source of truth とする契約を明示する。
  - _Requirements: 4.5, 12.5, 12.6, 12.8, 12.9, 12.10_

- [x] 6.5 `RunController` に cancel エンドポイントを追加する
  - Red: `POST /api/v1/runs/{runId}/cancel` が `202` / `200` / `404` と現在状態を返すテストを追加する。purge 済み run は `404`、runId なしのグローバル cancel API は公開しない。
  - Green: `RunController` に cancel ハンドラを追加する。
  - Refactor: 冪等性を確認する。
  - _Requirements: 4.5, 4.6, 12.3, 12.4, 12.5, 12.6_

- [x] 6.6 queue / runner を接続した cancel の競合を検証する
  - Red: dequeue 後〜`begin()` 前の cancel で本体処理が開始されず CANCELLED になることを、実際の queue / cancellation service を接続して確認する。
  - Red: RUNNING cancel で停止要求後も runner の `finally` が完了するまでは同一 project の次の run を開始しないことを確認する。
  - Red: 一つの run の cancel が他 project の run を停止せず、QUEUED cancel が先行 runner に影響せず後続の FIFO 順序を保つことを確認する。
  - Green: 開始・停止・queue 解放の連携を実装し、処理済み pending cancellation を回収する。
  - _Requirements: 9.3, 9.5, 12.6, 12.8, 12.9, 12.10_

### Phase 7: SSE

- [x] 7.1 `WebApiEventDto` を追加する
  - Red: `AgentEvent` を Web API 用 DTO に変換するテストを追加する。
  - Green: `WebApiEventDto` を実装する。
  - Red / Green: sequence と runId / sessionId / turnId / projectId の Envelope、event type、payload の外部 JSON 契約を固定し、内部 Java class 名や内部型の自動シリアライズに依存しないことを確認する。
  - Refactor: 外部 schema を固定する。
  - _Requirements: 13.3, 13.4_

- [x] 7.2 `SseBridge` を実装する（bounded queue + executor）
  - Red: `AgentEventBus` と `SseEmitter` の間に bounded queue を設け、専用 executor で書き込むテストを追加する。
  - Red: queue 上限到達時に drop せず error completion するテストを追加する。
  - Red: `onCompletion` / `onTimeout` / `onError` で unsubscribe するテストを追加する。
  - Red: writer の送信を意図的に止めても publish と他 run が進行すること、切断 / IOException / overflow で購読と heartbeat 等の接続資源を解放し、run 自体は cancel しないことを検証する。
  - Red: 対象 runId のみを送信し、Registry が CANCELLED の既存 runner の failed event は cancelled として扱うことを検証する（replay 側は 8.6）。
  - Green: `SseBridge` を実装する。
  - Green: 接続ごとの queue 上限は設計どおり 1,000 件とし、listener 内で `emitter.send()` や queue 空き待ちを行わない。terminal event の送信後に complete する。
  - Refactor: slow consumer / client disconnect / send IOException の扱いを整理する。
  - _Requirements: 5.6, 5.7, 5.8, 6.1, 6.2, 6.3, 6.4, 6.5_

- [x] 7.3 `SseController` を実装する
  - Red: `GET /api/v1/runs/{runId}/events` が `text/event-stream` を返すテストを追加する。
  - Red: terminal event 受信で `SseEmitter.complete()` するテストを追加する。
  - Red: heartbeat を 15〜30 秒周期で送信するテストを追加する。
  - Red: 不明 / purge 済み run の events 接続はストリーム開始前に `404` を返すことを確認する。通常イベントは `event` / `id` / DTO の `data` を持つ。
  - Green: `SseController` を実装する。
  - Refactor: heartbeat は `event: heartbeat` と `data: {}` のみで、`id` を持たず、global sequence を消費せず、ReplayBuffer に保存しない。通常イベントと同じ writer 経由で送り、接続終了時に停止する。
  - _Requirements: 1.4, 4.4, 5.7, 7.1, 12.3, 12.4_

### Phase 8: Replay

- [x] 8.1 `ReplayBuffer` を実装する
  - Red: runId ごとにイベントを保持し、`subscribe(fromSequence)` で replay するテストを追加する。
  - Red: bounded（run 単位で最大 10,000 events、run 完了後 30 分保持）を確認するテストを追加する。
  - Red: global sequence を run ごとに振り直さないテストを追加する。
  - Green: `ReplayBuffer` を実装する。
  - Refactor: `RunRegistry` と同一 retention に統一する。
  - _Requirements: 5.1, 5.2, 5.3, 5.10, 5.11_

- [x] 8.2 `AgentEventBus.subscribe(fromSequence)` を拡張する
  - Red: `subscribe(fromSequence)` で指定 sequence より後のイベントを replay するテストを追加する。
  - Green: `AgentEventBus` と `InMemoryAgentEventBus` を拡張する。
  - Red / Green: SSE 接続の有無にかかわらず publish 時から履歴を保存する。Last-Event-ID なしの初回接続で、POST 受理から接続までのイベントを replay できることを確認する。
  - Refactor: `lastSequence()` との整合を確認する。
  - _Requirements: 5.2, 5.4_

- [x] 8.3 SSE の replay とライブ購読の競合防止を実装する
  - Red: replay 境界の確定とライブ listener 登録を一貫した同期境界で扱うテストを追加する。
  - Red: replay 中に terminal event が発行されても確実に受信して complete するテストを追加する。
  - Green: 競合防止ロジックを実装する。
  - Refactor: replay とライブ配信の重複・順序逆転がないことを確認する。
  - _Requirements: 5.2, 5.5, 5.7_

- [x] 8.4 `Last-Event-ID` の replay と replay gap 判定を実装する
  - Red: `Last-Event-ID: 123` は 123 を再送せず、対象 run の sequence > 123 のみ replay する（次が 124 とは限らない）。他 run のイベントによる sequence の飛びを含めて検証する。
  - Red: 対象 run の未受信履歴が失われた場合だけ、HTTP 応答を commit する前に `409 Conflict` で接続を拒否する。他 run による sequence の飛び、eviction なし、失われたイベントを受信済みの場合を gap と誤判定しないことも確認する。`event: replay-gap` は送らない。
  - Red: 実行中 run に未来の Last-Event-ID を指定しても gap とせず、その接続後の新規イベントを最新境界から受信できることを確認する。
  - Red: 完了済み run への初回接続は保持された terminal event を replay して閉じる。synthetic な `run.status` は生成しない。
  - Red: `Last-Event-ID` が terminal event の sequence と同値 / 未来の場合、replay 対象が空でも即 `complete()` するテストを追加する。
  - Green: replay gap 判定と空 replay の終了条件を実装する。
  - Refactor: run 単位の metadata（`oldestRetainedSequence` / `latestSequence` / `evicted`）で判定する。
  - _Requirements: 5.4, 5.9, 5.11, 5.12_

- [x] 8.5 terminal 状態と空 replay の競合を実装する
  - Red: `CANCELLED` 確定と `agent.run.cancelled` 格納の間に SSE 接続しても、状態イベントを一度も送らず閉じないテストを追加する。
  - Red: terminal event が ReplayBuffer に未格納の場合はライブ購読で受信してから complete するテストを追加する。
  - Green: `terminalSequence` と ReplayBuffer 格納完了を終了判定に含める。
  - Refactor: 状態確定と terminal event 格納を購読開始と同じ同期境界で公開する。
  - _Requirements: 5.5, 5.7, 5.12_

- [x] 8.6 replay 時の変換契約を実装する
  - Red: `CANCELLED` 状態の run への再接続で、ReplayBuffer の replay 時に既存 runner の `agent.run.failed` が `agent.run.cancelled` として変換されるテストを追加する。
  - Green: replay 時の変換契約を実装する。
  - Refactor: `agent.run.failed` がそのまま流れて `agent.run.cancelled` と矛盾しないことを確認する。
  - _Requirements: 5.7, 12.7_

- [x] 8.7 retention と再接続を統合する
  - Red: terminal 確定から 30 分で RunRegistry / ReplayBuffer を同時に purge し、GET / events / cancel が一貫して `404` となることを確認する。片方だけ存在する状態を観測させない。
  - Red: disconnect / overflow 後、最終受信 ID で再接続すると未受信イベントを順序通りに取得し、terminal で終了することを確認する。
  - Green: 共通の期限と同期境界で purge を接続する。replay 最大 10,000 件と client queue 1,000 件を両立させ、健全な接続が履歴一括投入だけで常に overflow しない配送を実装する。
  - Refactor: replay 配送も publisher をブロックせず、ライブイベントとの順序と bounded queue を維持する。session の寿命は 4.5 の独立したポリシーに従う。
  - _Requirements: 5.2, 5.3, 5.4, 5.8, 6.1, 6.2, 12.3, 12.4; Design: retention / purge_

### Phase 9: 統合・回帰

- [x] 9.0 API 公開範囲と全体フローを検証する
  - 認証付き POST → QUEUED / RUNNING → SSE replay / live → 完了、失敗、cancel → 状態取得の各経路を、制御可能な runner で検証する。
  - 正しいキーでも後続 Phase の API、`/project cd`、`/sh` 等の非公開コマンドを呼び出せず、ShellCommand の自動公開がないことを確認する。
  - Controller が application service を呼び、ShellCommand を実行しないことをレビューする。
  - 要件 11.3 / 15.1 / 15.2 のサブコマンド別公開判断は、要件の公開表を参照する。後続 Phase の read / write / delete をこの Phase でまとめて公開しない。
  - _Requirements: 8.5, 11.1, 11.2, 11.3, 11.4, 11.5, 11.6, 13.1, 15.1, 15.2_

- [x] 9.1 全テストを実行する
  - `./mvnw.cmd test` を実行してすべてのテストが通ることを確認する。
  - 失敗するテストがあればここで修正する。
  - 完了条件: `JAVA_HOME=C:\Java\jdk-25` を設定して `mvnw.cmd test` が成功する。

- [x] 9.2 既存 CLI の回帰を確認する
  - 既存の `ReiApplication*` / `ChatCommand*` / `ConversationInputRouter*` テストが通ることを確認する。
  - カレントプロジェクトのグローバル状態からの引きはがしが既存 CLI を壊さないことを確認する。

---

## 注意事項

- **TDD の鉄則**: テストを書く前に実装を書かない。Red を確認してから Green に進む。
- **最小実装**: Green フェーズでは「動く最小限」を書く。きれいにするのは Refactor フェーズ。
- **小さいステップ**: 1 つの振る舞いを Red → Green → Refactor で確認する単位に分ける。コンポーネント横断の契約があるため、ファイル数は完了条件にしない。
- **コミット**: コミットする場合は、関連するテストと実装を同じコミットに含め、Green / Refactor の区切りを使う。
- **Web API DTO の分離**: 内部型（`AgentEvent` 等）を直接 expose しない。
- **API キー**: ログ / 例外 / イベントに出力しない。constant-time comparison で比較する。
- **カレントプロジェクト**: グローバル状態に依存しない。クライアントがチャット送信ごとに `projectId` を指定する。
- **runId の一貫性**: `ChatSubmitService` が採番した `runId` を内部で再採番しない。レスポンス / `RunRegistry` / `ConversationTurnStore` / `AgentEvent` で一致させる。

## 実装結果・検証（2026-09-15）

全40タスクを完了した。機能追加時は失敗するテストを確認してから最小実装を行い、Green と回帰確認の区切りでコミットした。依存3件は開始時点で追加済みだったため、再追加せず実際の Tomcat / Security / Actuator の統合テストで検証した。

| 対象 | 主な検証 |
| --- | --- |
| 起動・認証・公開範囲 | `WebApplicationTest` / `SecurityConfigTest` / `WebSettingsTest` / `WebApiIntegrationTest`。環境変数以外によるキー・起動判定の上書きを拒否し、実 HTTP で health・認証・対象外 API の非公開を確認 |
| 状態・チャット受付 | `RunRegistryTest` / `RunControllerTest` / `ChatSubmitServiceTest` / `ChatControllerTest`。状態遷移、期限、202 + Location、入力エラー、session 継続を確認 |
| FIFO・実行停止 | `ProjectRunQueueTest` / `RouterWebRunTest` / `RunLifecycleTest` / `RunCancellationTest` / `WebPreStartCancellationTest` / `WebBoundaryTest`。異常終了、executor 拒否、開始前 cancel、実際の後処理完了までの待機を確認 |
| project / session 分離 | `WebSessionExecutionTest` / `SessionWorkingSetTest`。実チャットサービス・会話履歴・Working Set・イベントの ID と CLI の project 切替からの独立性を確認 |
| SSE・replay | `WebApiEventDtoTest` / `SseBridgeTest` / `SseControllerTest` / `ReplayBufferTest` / `SseReplayTest` / `WebBoundaryTest`。HTTP フレーム、replay gap、切断・再接続、終端競合、heartbeat、購読解除、同時 purge を確認 |

- 初期実装は session を30分保持した。Session History 拡張後は30分保持を runtime cache のみに適用し、永続 Session は失効させない。
- Working Set は会話単位の scope と永続ファイルに分離し、CLI の既存保存先は維持した。
- 状態と metadata は monitor 内で一緒に更新し、immutable な snapshot として公開する。
- `JAVA_HOME=C:\Java\jdk-25` で `./mvnw.cmd test` を実行し、**1,763件、失敗0、エラー0、スキップ2、BUILD SUCCESS** を確認した。
- スキップ2件は既存の `ExternalAgentPolicyTest` のシンボリックリンク検証で、Windows のリンク作成権限がないため。Web 機能の追加テストにスキップはない。
- 最終実行ログ: `target/web-full-test.log`（ビルド生成物として Git 管理外）。

## 追加対応: プロジェクト一覧 API（2026-09-16）

- [x] 10.1 認証付き `GET /api/v1/projects` を追加する
  - Red: `ProjectControllerTest` を先行追加し、未実装クラスによるコンパイル失敗を確認した。
  - Green: `ProjectQueryService` / `ProjectController` / `ProjectResponse` と Web 有効時の Bean を追加した。
  - 認証なし・不正キーは401、正常時は200。空一覧、UUID・名前・パスの公開、登録順、登録内容の更新反映、取得によるファイル非変更を検証した。
  - `WebApiIntegrationTest` で実 HTTP による一覧取得と、返却 ID を使ったチャット受付202を確認した。
  - _Requirements: 8.7–8.10, 11.5; Design: GET /api/v1/projects_
- [x] 10.2 要件・設計・利用例を更新し、Web API の回帰テストを実行する
  - Phase 1 の公開対象を6エンドポイントに更新し、チャットの ID 例を UUID に修正した。
  - `./mvnw.cmd '-Dtest=dev.mikoto2000.rei.web.*Test' test`: **41件、失敗0、エラー0、スキップ0、BUILD SUCCESS**。
  - ログ: `target/project-api-regression.log`（Git 管理外）。上記1,763件の全体テスト記録は初回実装時の結果。
- [x] 10.3 同名プロジェクトを区別するため、一覧に `path` を追加する
  - Red: 別ディレクトリの同名プロジェクト2件を登録し、パス未返却によるテスト失敗を確認した。
  - Green: application service と公開 DTO にサーバー上の登録済み絶対パス文字列を追加した。
  - 要件8.7、設計のレスポンス例と PowerShell の表示例を更新した。
  - Web API 回帰テスト41件すべて成功（失敗0、エラー0、スキップ0）。ログ: `target/project-path-regression.log`。
