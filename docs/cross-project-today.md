# Cross-project Today / Daily Planning

`rei.web.enabled=true` と `rei.today.enabled=true` を明示設定する。Todayは既定OFF。
対象は登録済みProject IDのallowlist、最大16件。pathからProjectを作らず、全登録Projectへ
暗黙に広げない。allowlistが空、未知Project、無効なtimezone/上限なら起動を拒否する。

```yaml
rei:
  today:
    enabled: true
    projects: [REGISTERED_PROJECT_A, REGISTERED_PROJECT_B]
    zone: Asia/Tokyo
    max-tasks-per-project: 1000
    max-items-per-project: 128
    collection-budget-seconds: 5
    stale-after: 7d
```

環境変数は `REI_TODAY_ENABLED`、`REI_TODAY_PROJECTS`（comma区切りID）、`REI_TODAY_ZONE`。
外部設定テンプレートにも既定OFFと空allowlistを含む。

## HTTP

既存Bearer認証を使う `GET /api/v1/today`。allowlist全件を読み取る。
`?projectId=ID` または複数のprojectIdでその部分集合を指定できる。allowlist外/移動・削除後の
Projectは404、空・重複選択は400。GETだけを公開し、LLM、Tool、Work Context更新、
Dependency観測、Scheduler有効化、Goal実行、Task再開を呼ばない。

返却する構造はschemaVersion、calendar date/zone、generatedAt、method=DETERMINISTIC、
カテゴリ一覧、Projectごとのitems/activity/partial/warnings。
itemにはsource ID、title/reason、保存status、Session、更新/予定時刻、categories、tags、
certainty、最大32件の既存result参照を含む。文字列は資格情報をredactして最大512文字。
タイトルやreasonは保存データの表示であり、新しいAgent instructionとして実行しない。
複数カテゴリに該当するitemは一つのitemと複数categoriesで表す。

|カテゴリ|保存状態からの条件|
|---|---|
|Today|対象calendar dayのdue、または他カテゴリに属さない未完了Task/保存目的・作業|
|Overdue|due/deadlineが集計開始時刻より前|
|Waiting|TaskのWAITING状態|
|Blocked|BLOCKED/FAILED/UNKNOWN、または保存Work ContextのBLOCKER|
|Scheduled|既存Scheduler Task。PENDINGにはACTIVATION_REQUIRED tag|
|Suggested Next|保存NEXT_ACTION/VERIFICATION。USER/TOOL/ASSISTANT/INFERENCEを保持|

GoalはCURRENT_GOAL、明示再開済みCheckpointはRESUMED、更新がstale-afterより古いものはSTALE。
時刻のないRunは古いと推測しない。COMPLETED/CANCELLED Taskと完了・撤回・置換済みの
Work Context項目は候補から外す。期限超過を検出してもDependency状態は書き換えない。
PENDINGの予定を表示しても有効化しない。UNKNOWNを成功として扱わない。

Today有効化は既存Run Registryを永続化し、Shell Run登録にも接続する。
相談・READ並行モードと実行制御は別設定。再起動後の結果不明RunはUNKNOWNとして表示する。
既存Task Managerが無効でも同じ読み取りprojectionを再利用し、操作endpointや新queueを追加しない。

## 上限と根拠

Taskは既存keyset paginationで100件ずつ読み、既定1000/最大2000件で停止する。
Projectごとの表示は既定128/最大256件、result参照は32件。
切捨てにはpartial=trueとtask_limit_reached/item_limit_reached/task_references_partialを付ける。
収集予算は1–10秒、既存のbounded readの間で確認する協調的な上限。
収集途中の源を丸ごと読み終えたと偽らず、停止したProjectにcollection_budget_reachedを付ける。
全源の同時transaction snapshotではない。generatedAtは分類の基準時刻で、各sourceの状態は読み取り時のもの。

Work Contextは保存Git directoryが現在の登録rootと一致する場合だけ使用する。
古い未確認rootを補完しない。保存文脈がない場合は候補を作らない。
LLMを使わないため、モデルの出力を成功として検証する工程や追加のモデル予算はない。

Activityが有効な場合は既存のbounded observation-context取得を利用し、観測ID・時刻・item ID参照だけを返す。
provenanceはOBSERVATION_ONLY。観測だけから成果、集中、Goal完了、Task再開の必要性を断定しない。
Activityの日付とzoneはjournalの値を別フィールドに保持する。
観測の欠落・上限・取得不可はwarning/partialで示し、予定候補の捏造で補わない。

日時分類はtimezoneのcalendar day境界を使い、DSTの23/25時間日を24時間固定として扱わない。
この機能はHTTPから利用可能。Nativeの専用Today画面やLLMによる並べ替えは要求の最小公開範囲に含めていない。
