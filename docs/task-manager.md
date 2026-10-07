# Task Manager

`rei.task-manager.enabled=true` と既存の認証済みWeb APIを明示的に有効化する。
既定はfalse。並行相談・READモードは別設定 `rei.conversation.concurrent-enabled=true` が必要。
既存のRun、Checkpoint、Goal、Dependency、Scheduler、SubAgentを参照するprojectionであり、
別の実行queueやモデル予算を作らない。

## HTTP

すべて `/api/v1/tasks` 以下で、既存のBearer認証を使用する。

|操作|経路|内容|
|---|---|---|
|list|GET /tasks|projectId/sessionId optional、limit 1–100、cursor|
|get|GET /tasks/{id}|projectId必須、所有Sessionがある場合sessionIdも必須|
|submit|POST /tasks|projectId、sessionId optional、message、mode optional|
|cancel|POST /tasks/{id}/cancel|projectId、sessionId、expectedRunId、expectedRevision|
|suspend|POST /tasks/{id}/suspend|同上。再開元があるCheckpoint/実行中Goalのみ|
|resume|POST /tasks/{id}/resume|同上。既存の照合・権限・保存予算を再利用|
|input|POST /tasks/{id}/input|同上とmessage。選択したRunの**次のiteration**への入力|

submit/control成功は202と更新されたTaskを返す。submitはLocationも返す。
書込の未知field、欠けたrevision、空の入力、不正modeは400。
所有範囲の不一致は404、古いRun/revision、利用できない操作は409。
受付上限は既存のProject64/全体256、超過は429。messageは最大16384文字。
入力mailboxは既存の32件上限を使用する。追加指示は新しいRunを作成しない。
失敗した操作を別Chat受付に置き換えたり、自動再送したりしない。

## Identityと状態

Task IDは `run:`, `goal:`, `dependency:`, `schedule:` と元のIDからなる。
Checkpoint付きRunはoriginalRunIdをTask IDに使用し、再開後も同じTaskから新しいrunIdを参照する。
SubAgentは既存の子Run IDを使う。親Run・親Session・agent IDを共通Run metadataへ保存し、
親Sessionから一覧・取消できる。子取消は親を取消しない。子Runの自動再開は提供しない。

QUEUED/RUNNING/WAITING/BLOCKED/SUSPENDED/COMPLETED/FAILED/CANCELLED/UNKNOWNを表示する。
Goal READYはQUEUED、PAUSEDはSUSPENDED、WAITING_APPROVALはWAITING。
中断は現在の共通RunをCANCELLEDにする。Goalの既存callbackはPAUSEDへ遷移し、
Checkpointは保存内容を保持する。キャンセル済みRun自体を再実行せず、明示resumeで既存再開経路を使う。
進捗は保存されたplan statusやGoal試行件数であり、独立検証済み成果の件数を意味しない。
Artifact参照は共通Artifact Deliveryとの統合で追加する。

listは登録されたProject root内の資料だけを返す。Project除外・root変更後の古い記録は表示しない。
cursorはProject/Sessionに束縛したIDのkeysetで、実行状態変更によるページの重複を防ぐ。
資料源のページはSQLで上限を適用し、従来のrecent 256/1000件取得による切り捨てを回避する。
子/関連参照は表示ページの外にある場合も元repositoryと共通Runを参照する。

## 復元とNative

有効化時は既存SQLiteにRun metadataを保存する。PID/プロセス開始時刻と状態CASで所有権を守る。
別プロセスのRunは読取可能だが、そのプロセスの実行を取消・介入しない。
停止した所有プロセスの未終端RunはUNKNOWNになり、受付済み処理を自動実行しない。
裸Run/子Runの終端保持は既存30分。長期のCheckpoint/Goal/Dependency/Schedulerは元の保存規則に従う。
SubAgentの永続checkpoint・DAG再開は別機能であり、このmetadata保存とは区別する。

既存SQLiteへ `rei_run_registry` とDependency/Schedulerの `creator_run` columnを追加する。
既存Run JSONは省略された子metadataをnullとして読み取る。古いDependency/Schedulerで
creatorを確認できないものは親を推測しない。

Nativeの「Task Manager」は、起動後サーバー一覧を取得し、Project/Session絞り込み・ページング・
新規受付・中止・中断・明示再開・追加指示を提供する。操作直前にもサーバーから選択Taskを取得し、
Run ID/revision/操作可否を検査する。サーバー切替後の古い応答は捨てる。
Task一覧はローカルRun cacheを再実行する仕組みではない。API未有効のサーバーには取得失敗を表示する。

## 検証

TaskManagerService/ProjectionSources/SourcePagination/ProjectionRefresh/ControlService/ControlCas、
ShellTaskTracking、TaskController/TaskHttpIntegration、Checkpoint stale revision、SubAgentRunnerで
投影と実制御を検証する。1545件の保存資料を複数ページで取得するfixture、別JVMの強制終了、
実HTTPの既定OFF・Bearer・受付・再起動も含む。有料モデルは使わない。
Native tasks_http/tasks_serviceとReact Tasksで所有範囲、復元、操作payload、ページング、切替競合を確認する。
