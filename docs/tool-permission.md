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

readMultiFile/readRawToolResult/grepMultiQuery/readPdfFile/searchAndRead、today/now/findFile/listFile/getShellProcessStatus は READ、
webSearch/webSearchAndRead は NETWORK_READ、searchKnowledge は READ + NETWORK_READ。
applyTextDiff/writeMultiFile/createDirectory/copyFile は LOCAL_WRITE。
deleteFile/moveFile/killShellProcess は LOCAL_WRITE + DESTRUCTIVE。
組み込み Tool は実装の能力を明示分類します。履歴・活動・Task/Feed一覧は READ、Task/Reminder作成やWork Context更新は LOCAL_WRITE、
Task/Feed削除は LOCAL_WRITE + DESTRUCTIVE、Calendar参照は NETWORK_READ、Calendar作成/Bluesky投稿は NETWORK_WRITE + EXTERNAL_SIDE_EFFECTです。
論文取得・要約・翻訳はダウンロードと保存を行い得るため READ + NETWORK_READ + LOCAL_WRITEです。
外部CLIレビューには READ + EXECUTE + NETWORK_WRITE + EXTERNAL_SIDE_EFFECT、修正案保存にはさらに LOCAL_WRITE が必要です。
runCommand/executeExternalProgram、任意commandを使う修正・検証、Computer Use、任意Taskを再開するcheckpointResumeは全能力の保守的分類を維持します。
未知 MCPなど、未分類のcallbackも全能力として扱います。
管理者が安全に分類できる Tool のみ capabilities へ追加してください。

`delegateTask` / `delegateTasks` は入力の委譲先を確認し、管理者が登録した子定義とSubAgent allowlistから必要能力を解決します。
ローカル委譲は READ と各子の必要能力の和集合です。読み取りだけのreviewerは既定のREAD許可で動作し、web検索を使う子にはNETWORK_READも必要です。
並列委譲は全依頼の能力を実行前にまとめて判定します。chatgpt-planにはNETWORK_WRITE + EXTERNAL_SIDE_EFFECT、
codex-app-serverにはさらにEXECUTEが必要です。外部バックエンド内部の資料・送信承認も別途適用し、入口の承認だけでは解除しません。
未知のagent/backend、不正入力、利用できない定義、allowlist外の子Toolは全能力に倒します。管理者の分類上書きも委譲の必要能力を減らしません。
子Toolの実行時権限・親のREAD_ONLY/CONVERSATION/MCP制限・再帰委譲禁止は維持します。
delegateTaskGraphとjudgeSubAgentAnswersも登録子の能力を確認します。Graph登録にはLOCAL_WRITEも必要です。
保存したローカル子/Graphの再開はREAD + NETWORK_READ + LOCAL_WRITEと、既存の明示再開条件を必要とします。
組み込みToolの分類漏れは `BuiltinToolClassificationTest` で検出します。

確認必要/禁止時は callback を呼ばず、所有 Project/Session/Run を持つ既存 `tool.failed` を発行します。
errorType は PermissionRequired / PermissionDenied、code は REQUIRE_APPROVAL / DENY。
引数をイベントへ追加しません。Shell のエラー表示、Agent UI Projection、Web SSE が既存経路で通知します。
監査イベントの保存に失敗した場合も実行しません。

## 一回限りの承認と再開

確認が必要な Chat Tool は実行せず、SQLite に PENDING request を保存します。Shell/CLI の通知には操作内容・期限と「この操作を承認して続行するには、次のコマンドを入力してください」を表示し、`/approval approve <approvalId> --continue` をコピーできる独立した行で案内します。`list` で要求を探す必要はありません。詳細確認と拒否のコマンドも同じ通知に表示します。
対話端末では、通知後に通常の入力欄を空にして上下キーを押すと「詳細確認」「承認して続行」「拒否」を選べます。最初は詳細確認を選択し、Enterで表示されたコマンドを実行します。複数の要求がある場合は上下キーで各要求の操作を順に選びます。入力中の文章、複数行入力や別の確認プロンプトには割り込みません。Project/Session変更後は前の選択を実行しません。コマンドの手入力も引き続き利用できます。
通常のChatではRunとチェックポイントを `WAITING_APPROVAL` にし、未実行のTool呼び出し・正確な引数・会話・残予算を保存して実行スレッドを解放します。
承認待ちはRun失敗ではなく、`RUN_FAILED` 通知を出しません。同じTool応答内で先に成功した呼び出しは保存し、続行時には繰り返しません。

```text
/approval list
/approval show 1
/approval approve 1 --continue
/approval deny 1
/resume list
/resume <taskId>
```

`show` で Tool 名、資格情報を伏せた引数、元の Run / Session、期限を確認してから approve / deny を実行します。
`--continue` は承認と続行受付を同じSQLite transactionで保存します。保存した呼び出しを現在の権限・Project/Session・取消状態で再確認し、
正確な引数で一度だけ実行してTool結果を会話に戻してからモデルの続きを呼びます。モデルに承認済み引数を再生成させません。
`/approval approve <approvalId>` は承認のみを保存します。続行する場合は同じIDに `--continue` を付けてください。
一覧の番号は表示した要求とClient・Project・選択Sessionに固定し、別の要求へ再割当てしません。選択変更・端末再起動後は一覧を再表示してください。UUIDも利用できます。
Shell の `/approval show` と `/approval approve` は、要求のRunをチェックポイント履歴と照合してtask IDも表示します。
この改修以前の保存呼び出しを持たない要求には従来の `/resume <taskId>` を案内します。承認ID・通知ID・Run IDは `/resume` の引数ではありません。
照合できない場合は `/resume list` でtask IDと依頼内容を確認し、必要なら `/resume show <taskId>` で詳細を確認してください。
完了・除外済み・実行中のタスクや拒否済み承認には再開コマンドを表示しません。
従来の再計画で引数が変われば新しい承認が必要です。JSON のキー順・空白が変わった場合も完全一致しないため再確認します。

承認は Project / Session / Tool / Project の絶対パスと引数の SHA-256 に固定し、15分で失効します。Project を移動すると承認は使えません。PENDING → APPROVED / DENIED → CONSUMED の遷移です。
実行前の単一 SQL update で承認を消費するため、並行 Run・別プロセス・再起動でも再利用できません。
callback 失敗、キャンセル、監査失敗でも消費済み承認は復活しません。DENY は承認で解除できません。
保存先は memory-consolidation.db の tool_approvals。承認レコードはSHA-256とCredentialRedactor適用後のpreviewを保存します。
`approval_continuations` は正確な再実行に必要な引数原文・非System会話・成功済みTool結果・残予算を保存します（最大1MiB・256messages・32calls）。
画面・通知にはredacted previewのみ表示します。原文を持つ継続データはローカルDBの保護対象です。
16,384文字を超える引数は承認要求を拒否します。一覧は最大256件で、有効な承認に加えて続行中・期限切れ・結果不明のreceiptも表示します。
続行受付はWAITING_APPROVAL → QUEUED → RESUMING → COMPLETEDとして永続化し、二重クリック・重複要求は同じreceiptを返します。
QUEUEDは再起動後も実行可能ですが、RESUMINGの所有プロセスが失われた場合はUNKNOWNにし、自動再実行しません。
期限切れ・拒否・取消しは実行せず終了します。管理されたGoalの親予算が再起動で失われた場合もUNKNOWNとし、新しい予算で勝手に再開しません。

Web API は既存 API キー認証下で `GET /api/v1/projects/{projectId}/approvals`、
`GET /api/v1/projects/{projectId}/approvals/{approvalId}`、
`POST /api/v1/projects/{projectId}/approvals/{approvalId}/decision`（承認のみは `{"approved":true}`、拒否はfalse、承認して続行は `{"approved":true,"continueRun":true}`）を公開します。
一覧・詳細・判断結果にはcontinuationStateも返します。`GET .../{approvalId}/continuation` で続行状態を参照できます。承認操作はモデルToolとして公開しません。

Native Clientの通知・承認画面で内容を確認して「承認して続行」を選べます。「一回だけ承認」は承認のみを保存します。
SubAgent は呼び出しごとに独立 Session なので承認の継承・再利用は行わず、従来どおり拒否イベントで停止します。

Policy 自体は LLM を呼びません。起動時に設定を読み込み、プロセス内で固定します。
Tool の引数（対象 path/URL/command）単位の制限、直接 slash command、背景 timer の内部処理、
LLM provider の通信、Tool callback 以外の I/O はこの境界の対象外です。
これを OS sandbox の代替や自律実行全体の権限保証として扱わないでください。

## 要件付き Codex 実装

requestCodexImplementation は既存の任意テスト実行相当の capability を維持します。Policy enabled かつ明示 AUTO_APPROVE と管理者 implementation opt-in がそろう場合だけ自動許可します。Policy disabled は自動許可の根拠になりません。仕様全文・Project・base commit・管理者 test recipe を既存の exact approval へ結び付け、実行直前に再検証します。UNKNOWN retry は新しい ID と明示承認が必要です。prepare はプロセスを起動せず、recordCodexAcceptanceEvaluation は LOCAL_WRITE、状態・receipt・diff は READ です。[具体的な設定と承認フロー](llm-codex-implementation-delegation.md#設定と認可)を参照してください。
