# Dotポーリング委譲MVP

## 責務と既存基盤
ポーリングはてんてん側の責務、依頼管理と結果保存はれい側の責務。れいはMCPクライアントを定期実行しない。外部の1分周期実行、Secure MCP Tunnel、OAuth2、MCP Events、Webhook、新Backend JVMは追加・変更しない。

|既存実装|今回の利用|
|---|---|
|McpConfiguration/McpIngressFilter|既存6ツールに固定4ツールを追加。HTTP境界を維持|
|StorageDatabase/StorageMigrationCoordinator|storage.dbの単一writer、バックアップ付きschema 7→8|
|ProjectService/SessionRepository/RunRegistry|所有Project・root・Session・Runの照合|
|ExternalAgentAuthorization/RunExecutionContext|明示依頼の保守的判定、共有委譲回数・モデル予算|
|ToolPermissionPolicy/Guard|公開用snapshotのNETWORK_WRITE+EXTERNAL_SIDE_EFFECT検査|
|PersistentDependencyRepository/DependencySourceProbe|DOT_REQUEST条件。外部の完了自己申告で充足しない|
|StagnationChatModel/ChatExecutionService|tool結果を保存後にWAITINGへ移り、モデル呼び出し停止|
|PersistentAgentScheduler/AgentScheduleDispatcher|手動承認済みintentの一度だけの再開予約|
|PersistentCheckpointService/ConversationInputRouter|revision・lease・所有権を確認した新Runの受付|
|DotRequestRepository/Payload/McpTools/ResumeService|今回必要な永続inbox、DTO境界、検証・再開|

既存の同期ExternalAgentプロセス実行は外部ポーリング結果待ちに流用しない。既存RunContinuationのGoal予約を変更しないため、MVPでは管理されたGoalの親予約を持つRunのDot委譲を拒否する。通常の人間入力を受けたEXCLUSIVE Runが対象。自動再開は未実装で、auto-resume=trueは起動時に拒否する。

## MCP契約
全出力schema_version=1。principalはlocal-mcp固定。Projectの許可はmcp-server.allowed-projectsとdot-delegation.allowed-projectsの共通範囲。ルートの絶対パス・Run内部情報・会話全文を公開しない。

|Tool|引数|権限/応答|
|---|---|---|
|rei_requests_list|project_id任意、status=PENDING、limit=5(最大20)、cursor任意|INBOX_READ。requests配列とnext_cursor。UUID順の安定keysetページ、空は[]|
|rei_request_get|request_id必須(UUID)|INBOX_READ。instruction/context_summary/expected_output/constraints/acceptance_criteria、status、期限、検証・resume状態|
|rei_request_claim|request_id、claim_id必須(UUID)|INBOX_CLAIM。原子的claim、lease期限。同一claim_idは元の応答、別IDはCLAIM_CONFLICT|
|rei_request_result_submit|request_id、claim_id、submission_id、outcome、summary必須|INBOX_RESULT_WRITE。persisted receipt。任意content/evidence/unresolved_items/acceptance_results|

公開snapshotのexecution_scopeは固定RESEARCH_ONLYで、Shell・ファイル変更・Git操作を依頼する権限は含まない。

outcomeはCOMPLETED/PARTIAL/FAILED/UNKNOWN。evidenceはtitle/url/description、最大20件、HTTP(S)のみ。acceptance_resultsはcriterion_index、SATISFIED/UNSATISFIED/UNKNOWN、evidence_indices。indexは範囲内・重複なし。未知フィールド（reasoning等）を拒否する。

submit成功はpersisted=true、validation_status=PENDING_REVIEW、resume_status=NOT_REQUESTED。**検証完了やRun再開の成功を意味しない**。同じsubmission_id・同じ入力は同じreceipt。内容変更、別claim、期限・lease切れ、取消を拒否。receipt再送は過去の保存証明を返し、lease延長や新実行を行わない。既存MCP HTTP ingressのJSON全体64KiB制限も適用されるため、64KiBのcontentにメタデータを加えた要求は収まらない。

## 状態と保存
依頼: PENDING → CLAIMED → RESULT_RECEIVED → 人間がVERIFIED/PARTIALLY_VERIFIED/REJECTED/FAILED。
未claim期限切れはEXPIRED。claim lease/期限切れはUNKNOWN（PENDINGへ戻さない）。人間がCANCELLEDにできる。取消はれい側の採用・再開を止めるもので、外部ですでに進行している調査プロセスを停止する通知ではない。UNKNOWNは自動再依頼せず、人間が確認・取消する。

再開intent: NOT_REQUESTED → 人間がREQUESTED → schedulerがSTARTED → ADMITTED。
ADMITTEDはrouter受付済み。新RunのQUEUED/RUNNING/COMPLETED/FAILED/CANCELLED/UNKNOWNとは別。status APIでresumed_run_id/resumed_run_statusを確認する。元Runは履歴としてWAITINGを保持する。
STARTEDのまま再起動、またはREQUESTEDでschedule紐付け前に中断した場合はUNKNOWN。自動再予約しない。REQUESTEDに紐付いた既存scheduleの処理だけ継続できる。

storage.db:
- dot_requests: 所有者、公開snapshot、状態、期限、claim、親checkpoint/dependency、権限記録
- dot_receipts: requestごと1結果、submission一意、入力fingerprint、不変receipt
- dot_resume_intents: requestごと1intent、schedule/newRun/revision
- dot_audit: operation・結果・actor（本文は記録しない）

resultと初期resume intentは同一SQLite transaction。依頼DBと既存dependency/scheduler DBは別なので、親依頼は作成→dependency/checkpoint紐付け→tool結果保存→Run WAITING→公開の段階的受付。公開前の中断はUNKNOWN。再開もintent→既存schedule作成→紐付け→activationで、曖昧な中断を再実行しない。
結果本文30日、公開依頼/監査90日。冪等性のidentity/fingerprint/receipt tombstoneは保持する。UNKNOWN未解決依頼は容量を占有するので確認・取消が必要。内部maintenanceは期限・保持期間処理だけを行う。

## 設定
既定はすべて委譲無効。既存application.yamlのreiに以下を統合する（同じキーを重複作成しない）。
```yaml
rei:
  dot-delegation:
    enabled: true
    parent-llm-enabled: false # 親LLMを使う場合のみtrue
    auto-resume: false
    allowed-projects: fc9ecc6d-1aaa-43e1-a11a-1cd556e85b68
    allowed-categories: TECH_RESEARCH,COMPARISON,REVIEW
    active-limit: 2
    queue-limit: 8
    default-timeout: 1h
    claim-lease: 30m
    result-retention-days: 30
    request-retention-days: 90
```
MCPの既存enabled/port/allowed-projectsは維持する。同時未完了はCLAIMED・RESULT_RECEIVED・UNKNOWNの合計2件、PENDINGを含む未完了合計10件。期限最大24h、lease最大30m。保持期間・容量は安全上限内で縮小可能。
親LLMと手動再開にはrei.conversation.concurrent-enabled=true、rei.checkpoint.enabled=true、rei.tool-permission.enabled=true、再開にはrei.agent-scheduler.enabled=trueが必要。dependencyの自動状態表示はrei.dependency-watcher.enabled=trueを使う。権限設定は既存[tool-permission](tool-permission.md)に従う。NETWORK_WRITEとEXTERNAL_SIDE_EFFECTを全ツールへ無条件許可する必要はない。

## CLIとWeb
legacy-shellと軽量CLIは共通の固定grammar・ドメインAPIを利用。汎用Shell転送はない。
```text
/dot request "WindowsとLinuxの違いを調べて" --category COMPARISON --context "対象は起動処理" --expected-output "出典付き比較表" --acceptance-criterion "一次資料で比較する" --timeout 3600
/dot requests
/dot status <request-id>
/dot result <request-id>
/dot cancel <request-id>
/dot verify <request-id> --status VERIFIED --satisfied 0 --note "一次資料と照合済み" --yes
/dot resume <request-id> --yes
```
--project-idで許可Projectを選択。作成の再送には--request-id <UUID>を固定する。同じID・同じ公開specification/所有者/timeoutは同じ依頼、変更はREQUEST_CONFLICT。軽量CLIは送信RequestにUUIDを付与する。legacy-shell/Webは固定IDを指定しない新しい作成操作ごとに新IDを発行する。通信断後に新IDで自動再作成しない。--constraint/--acceptance-criterionは繰り返し可能。--satisfiedはゼロ始まりindexのカンマ区切り。VERIFIEDには全criteriaと人間の検証noteが必要。criteriaなしの場合も明示検証noteと--yesが必要。CLIのstandalone依頼は親Runを持たないため、結果参照・検証は可能だがresumeはNO_PARENT_RUN。

Webは既存Bearer認証下の/api/v1/projects/{projectId}/dot-requests。POST create（request_id任意）、GET list/id/id/result、POST id/cancel・id/verify・id/resume。検証bodyはconfirmed/status/note/satisfied、再開はconfirmed。MCPにはverify/resumeを公開しない。

## 親LLM
人間が通常のWeb/Shellで「てんてんに技術調査を依頼してください」などを明示。管理者Project/category opt-inとRun単位の人間入力許可を満たす場合だけrequestDotResearchを使える。category、instruction、context_summary、expected_output、constraints、acceptance_criteria、timeoutを渡す。Project/Session/RunはToolContextから固定し、モデルに指定させない。
MCP由来、READ_ONLY、サブエージェント、音声、scheduler生成文、引用文・否定依頼、管理Goal予約からは委譲しない。ExternalAgentと共有のRun内1回制限、モデル呼び出し予算を消費する。再起動で人間入力の許可は失効。
ツール結果をcheckpointへ保存してからWAITINGに入り、workerを解放。人間が外部結果を検証・resumeし、既存checkpointを新Runとして再開する。新RunはREAD_ONLYかつMCP sourceで、副作用ツールやDotへの再委譲を拒否する。外部結果はEXTERNAL_RESULT_UNTRUSTED証拠として扱い、元の指示を置換しない。書き込み作業を継続する場合は人間が通常の別入力で承認する。

## 拡張点
将来のMCP Eventsは「新しい公開依頼がある」という通知に限定し、このinbox/claim/receipt/intent契約を共用する。lease延長、自動検証・再開、管理Goalとの予算予約統合、tombstoneの容量上限は別課題。
