# Tool Permission Policy

`rei.tool-permission.enabled=true` で、Chat と SubAgent の Tool callback 実行直前に共通 Policy を適用します。
既定では無効で、既存 SubAgent allowlist / Computer Use SafetyPolicy / 外部 review の明示要求条件は引き続き適用されます。

```yaml
rei:
  tool-permission:
    enabled: true
    auto-approve: [READ, NETWORK_READ]
    denied: [DESTRUCTIVE]
    capabilities:
      myReadOnlyMcpTool: [NETWORK_READ]
```

能力は READ、LOCAL_WRITE、EXECUTE、NETWORK_READ、NETWORK_WRITE、EXTERNAL_SIDE_EFFECT、DESTRUCTIVE。
判定は AUTO_APPROVE、REQUIRE_APPROVAL、DENY。禁止能力が1つでもあれば DENY、必要能力がすべて auto-approve にあれば AUTO_APPROVE、
それ以外は REQUIRE_APPROVAL です。分類の上書きは管理者設定に限定します。一回限りの承認は下記の明示操作で発行し、LLM の引数や自然言語の「承認済み」では解除しません。

readMultiFile/grepMultiQuery/readPdfFile/searchAndRead、today/now/findFile/listFile/getShellProcessStatus は READ、
webSearch/webSearchAndRead は NETWORK_READ、searchKnowledge は READ + NETWORK_READ。
applyTextDiff/writeMultiFile/createDirectory/copyFile は LOCAL_WRITE。
deleteFile/moveFile/killShellProcess は LOCAL_WRITE + DESTRUCTIVE。
その他は任意の能力を使える可能性があるため全能力として扱います。runCommand/executeExternalProgram、
未知 MCP、Computer Use、外部 delegation もこの保守的な既定値の対象です。
管理者が安全に分類できる Tool のみ capabilities へ追加してください。

確認必要/禁止時は callback を呼ばず、所有 Project/Session/Run を持つ既存 `tool.failed` を発行します。
errorType は PermissionRequired / PermissionDenied、code は REQUIRE_APPROVAL / DENY。
引数をイベントへ追加しません。Shell のエラー表示、Agent UI Projection、Web SSE が既存経路で通知します。
監査イベントの保存に失敗した場合も実行しません。

## 一回限りの承認と再開

確認が必要な Chat Tool は実行せず、SQLite に PENDING request を保存します。イベントのメッセージに approvalId が表示されます。

```text
/approval list
/approval show <approvalId>
/approval approve <approvalId>
/approval deny <approvalId>
/resume list
/resume <taskId>
```

`show` で Tool 名、資格情報を伏せた引数、元の Run / Session、期限を確認してから approve / deny を実行します。
承認自体は Tool の呼び出しも LLM の呼び出しも行いません。既存 Checkpoint の taskId で明示 Resume するか、
同じ Session で要求を再実行してください。新しい Run が同じ Tool・完全一致の引数文字列を選んだ場合だけ承認を消費します。
再計画で引数が変われば新しい承認が必要です。JSON のキー順・空白が変わった場合も完全一致しないため再確認します。

承認は Project / Session / Tool / Project の絶対パスと引数の SHA-256 に固定し、15分で失効します。Project を移動すると承認は使えません。PENDING → APPROVED / DENIED → CONSUMED の遷移です。
実行前の単一 SQL update で承認を消費するため、並行 Run・別プロセス・再起動でも再利用できません。
callback 失敗、キャンセル、監査失敗でも消費済み承認は復活しません。DENY は承認で解除できません。
保存先は memory-consolidation.db の tool_approvals。引数原文は保存せず、SHA-256 と CredentialRedactor 適用後の preview を保存します。
16,384文字を超える引数は承認要求を拒否します。一覧は有効な PENDING / APPROVED の最大256件です。

Web API は既存 API キー認証下で `GET /api/v1/projects/{projectId}/approvals`、
`GET /api/v1/projects/{projectId}/approvals/{approvalId}`、
`POST /api/v1/projects/{projectId}/approvals/{approvalId}/decision`（`{"approved":true}` / `false`）を公開します。
その後は既存 Checkpoint Resume API を明示実行します。承認操作はモデル Tool として公開しません。

Native Client の専用承認ボタン、実行スレッドの保留と自動再開は未対応です。
SubAgent は呼び出しごとに独立 Session なので承認の継承・再利用は行わず、従来どおり拒否イベントで停止します。

Policy 自体は LLM を呼びません。起動時に設定を読み込み、プロセス内で固定します。
Tool の引数（対象 path/URL/command）単位の制限、直接 slash command、背景 timer の内部処理、
LLM provider の通信、Tool callback 以外の I/O はこの境界の対象外です。
これを OS sandbox の代替や自律実行全体の権限保証として扱わないでください。
