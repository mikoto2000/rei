# MCP Server MVP

れいの既存 Spring Boot 4.1.1 / Spring AI 2.0.1 Backend に、本人専用の MCP 入出力アダプターを追加する。別 JVM や別のジョブスケジューラーは起動しない。

## 接続と設定

既存 Backend の Web 起動には `REI_API_KEY` が必要。既存 API の Bearer 認証はそのまま使用する。MCP 自体は No authentication、固定 principal `local-mcp`。

```powershell
$env:REI_MCP_SERVER_ENABLED = 'true'
$env:REI_MCP_SERVER_PORT = '8091'
$env:REI_MCP_SERVER_ALLOWED_PROJECTS = '<登録済みProject ID>,<別の許可Project ID>'
$env:REI_CONVERSATION_CONCURRENT_ENABLED = 'true'
```

- URL: `http://127.0.0.1:8091/mcp`
- transport: Streamable HTTP、Spring AI の SYNC MCP Server
- MCP は既定で無効。許可 Project は既定でゼロ。
- READ_ONLY 並列会話が無効なら新規依頼を `FEATURE_DISABLED` で拒否する。EXCLUSIVE に格上げしない。
- MCP ポートは通常 Web ポートと異なる番号を指定する。MCP は root servlet mapping を必要とする。
- Spring AI の内部 ToolCallback 自動変換と annotation scanner は無効。MCP client tools を転送公開しない。
- 通常 Web のバインドアドレスは変更しない。追加 Tomcat connector のみ `127.0.0.1` にバインドする。

## 実行経路

MCP SDK → `McpIngressFilter` → `McpTools` → `ChatSubmitService.submitMcp` → 共通 `SessionLifecycle` / `ConversationAdmissionStore` → 既存 `ConversationInputRouter` / `ProjectRunQueue` → 既存 runner / ToolPermissionGuard。

Session・Run・MCP所有権・受付 fingerprint を既存 `storage.db` の同じトランザクションで確定してから非同期 enqueue する。MCP Session 作成・継続は Shell の Project / Session 選択通知を発生させない。MCP HTTP session は SDK が管理し、会話 session_id と別物である。

## ツール契約

| ツール | 入力 | 主な出力 |
|---|---|---|
| `rei_projects_list` | なし | schema_version=1、projects(project_id/name/capabilities)。許可済み最大100件。パスなし |
| `rei_context_get` | project_id、任意 revision | revision、updated_at、summary、decisions、pending_items、evidence、source_type、truncated |
| `rei_task_submit` | project_id/instruction/request_id、任意 session_id/constraints/acceptance_criteria | request_id/run_id/session_id、admission_status、run_status、created_at、replayed |
| `rei_task_status` | project_id、run_id または request_id の片方 | state、task_id、session_id、waiting_reason、progress、approval、checkpoint_ref、updated_at、retry_after_seconds |
| `rei_task_result` | project_id/run_id、任意 cursor/max_chars | state、completion、content、artifacts、unresolved_items、next_cursor、truncated |
| `rei_task_cancel` | project_id/run_id | cancel_request_status、run_status、cleanup_status、rollback_status |

未知の引数・実行モード指定を拒否する。instruction は UTF-8 16KiB、request_id は128文字、constraints / acceptance_criteria は各最大16件・各2KiB、JSON本文は64KiB。受付後は実行完了を待たずに返す。task_id は保存済み Checkpoint があるときだけ識別可能で、会話Session IDやRun IDから生成しない。

status の progress は根拠のない割合を返さず null。WAITING を承認待ちと断定しない。approval は現在の保存済み承認要求のみを別フィールドで返し、Runが停止済みなら resume_required=true。MCPに承認・resume・inputツールはない。

結果は保存済み final assistant message から秘密情報を既存 CredentialRedactor で伏せ、immutable snapshot として保存する。内部推論・tool生ログ・任意ファイル本文は対象外。成果物は所有Runの安全なメタデータのみ。正常実行と受け入れ条件検証は別である。既存 Goal / Reflection の保存済み検証と同じ条件文が対応した場合にだけ VERIFIED / PARTIALLY_VERIFIED とし、自然言語の自己申告だけなら UNVERIFIED、条件なしなら UNKNOWN。拒否された検証は FAILED。

結果の既定ページは8,000、最大16,000 Unicode文字、本文UTF-8上限32,000 bytes。cursor は永続秘密鍵の HMAC-SHA256 で署名し、Project / Run / immutable snapshot に束縛する。cursorや不明状態を理由に新規依頼を投入しない。

業務エラーは MCP CallToolResult.isError=true と schema_version=1/error(code,message,retryable,correlation_id) を返す。不正JSONなどのプロトコル処理は Spring AI / MCP SDK に委ねる。他Project・非MCP Session/RunはNOT_FOUNDとして存在を隠す。

## 永続化と保持

Storage schema v6 → v7 を既存 StorageMigrationCoordinator で自動Migrationし、既存データを保持する。追加テーブルは mcp_requests、mcp_sessions、mcp_audit、mcp_settings。実行状態は conversation_admissions、会話本文は既存 ConversationTurnStore が正本。

同一 local-mcp + request_id の正規化 payload fingerprint が一致すれば同じRunを返す。キー順に依存しない。違う内容なら REQUEST_CONFLICT。受付は既定90日、結果と成果物メタデータsnapshotは終端日時から30日、監査は90日。`rei.mcp-server.receipt-retention-days` / `result-retention-days` / `audit-retention-days`（1〜3650日）で変更できる。結果本文削除後も受付の最小メタデータをtombstoneとして保持し、期限切れキーは再使用できない。受付期限を過ぎ、終端結果本文も削除済みになった行はacceptance_criteria本文を消し、最小tombstoneを残す。既存Conversation/Work Context自体の保持ポリシーは変更しない。

Run cleanup直後に結果を確定し、定期処理とresult取得でも保存済み情報から補完する。再起動復旧は既存 admission の所有プロセス判定を再利用し、不明なRunを自動再投入しない。SSEなしでもstatus/resultを照会できる。

## 公式資料とバージョン

- [OpenAI Secure MCP Tunnel](https://developers.openai.com/api/docs/guides/secure-mcp-tunnels)
- [OpenAI custom MCP server](https://developers.openai.com/api/docs/guides/custom-mcp-server)
- [MCP specification](https://modelcontextprotocol.io/specification)
- [Spring AI MCP Server Boot Starter](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html)

2026-10-12確認。公式MCP specificationページは2026-07-28版へ転送される。実際のwire protocol negotiationはSpring AI 2.0.1が採用するJava SDK 2.0.0の対応仕様に従う。旧SSE starterの独自実装は採用しない。Spring Boot 4.1では追加connector APIは addAdditionalConnectors である。

