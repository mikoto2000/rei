# Goal Stage 調査と実装記録

## Phase 0 — 基準と再利用方針

開始時 main: `c6da942a277fc47a827e4ed9f37fc7b850227c23`。

- PlanStep / ActionPlan は description・表示状態・順序・失敗回数を持つ実行案内。独立した成果検証契約はない。
- GoalLoopService は既存 Chat gateway を dispatch し、成功応答後も FileGoalVerifier で検証する。
  GoalRepository の claim token、Run と attempt の照合により遅延通知を拒否する。
- GoalRepository は SQLite の追加列・追加テーブルによる非破壊移行を採用する。
  Run は Goal attempt の run ID で関連する。LLM 呼出しは事前予約、token 不明は停止する。
  既定上限は最大10 Run / 100 call。Stage 追加後も変更しない。
- FileGoalVerifier の Path + FileCriterion 契約は SHA / JSON scalar / 宣言的 predicate に再利用可能。
  GoalCompletionGate は所有者・現在patch・レビューreceipt・テストreport・Artifact SHAと有効期限を照合する。
  Stage のための仮 Goal は作らず、検証対象の所有者と定義を受け取る共通契約を抽出する。
- GoalRepairDiagnosis は host の失敗理由だけから分類し、不明な外部操作を再試行可能とはしない。
- Checkpoint は承認・副作用の記録を持ち、GoalWaitService は依存条件と snapshot を照合して既存 loop を再開する。
  SubAgentDagService の実行と親予算予約を再利用し、新しい実行ループや子エージェントを追加しない。
- completion-progress API / /goal progress は独立検証結果を表示する。Stage と残予算を加算的に公開する。
- TimingExecution の COMPLETION_VALIDATION span、GoalCompletionEvaluation と baseline fixture が利用可能。

不足: 工程identity、工程証拠の版、依存工程の失効伝播、工程の試行履歴、工程単位の有限修復、
親条件を保持する再分割、計画変更CAS。これらを既存 loop と同じ SQLite transaction 境界に加える。

## 測定

既存の決定的baseline:

```powershell
./mvnw.cmd -Pfull '-Dtest=GoalCompletionEvaluationTest,GoalCompletionBaselineTest' test
```

出力は `target/goal-completion-baseline.json`。既存の10シナリオと指標定義は
[既存評価](goal-completion-evaluation.md) を参照。Stage 固有の10シナリオを追加し、
同一Goal条件・予算で既存 / Stage / 修復 / 分割の4設定を比較する。
モデルはこの測定に使わない。token は取得不可と記録し、0をモデル実測値として扱わない。

## Phase 1 — 永続契約

GoalStageDefinition は安定ID・親・依存ID・Goal要件ID・条件を保持する。
Stage の planVersion と状態 version、Attempt の独立UUID / Run参照 / 試行番号を分ける。
Run ID に UNIQUE 制約を置かず、将来の複数Stage/Runに備える。
証拠は attempt・planVersion・条件JSONの版・現在file SHA・依存状態version・結果・UTC時刻・証拠IDを保存する。
過去証拠と計画履歴は上書きしない。Stageの現在証拠参照だけ更新する。

追加テーブル: agent_goal_stage_plans / agent_goal_stages / agent_goal_stage_attempts /
agent_goal_stage_evidence / agent_goal_stage_plan_history。CREATE IF NOT EXISTS による追加のみで、
旧GoalやRunの行を削除・再作成しない。GoalRepositoryのJDBC/transactionを共有する。
保存するStage条件は現時点では不変。条件緩和はAPI・LLMとも拒否する。
Goal Completion Gate の Subject 契約を抽出し、同じ所有者照合と現在証拠検証をGoal/Stageで共有する。

Red: GoalStageDefinition未実装によるtestCompile失敗を確認。
Green: GoalStageRepositoryTest / GoalCompletionGateTest / JsonFileGoalTest /
GoalCompletionEvaluationTest / GoalCompletionBaselineTest 計29件成功。
最初のbaseline実行は未実装テストの追加と競合したため無効。上記Green実行でStageなしの旧経路を再測定した。
legacy fixture の独立条件成立率は7/10、応答中の誤完了申告率は3/10。
これはhostが誤ってCOMPLETEDにした率とは別で、実LLMの改善率でもない。

## Phase 2 / 3 — 既存Loop内の実行・再開・修復

Phase 2の工程/旧Goal/再開/Wait回帰34件、Phase 3の工程修復/旧修復/token回帰28件が成功。
GoalLoopService.nextの既存予算確認・beginAttemptとStage Attempt admissionを同じtransactionへ入れる。
成功callback後のStage証拠・状態とGoal attempt記録も同じtransaction。重複通知は既存claim/RunとAttemptで拒否。
プロセスがcommit後・次dispatch前に終了した場合も、明示reconcile後はVERIFIED工程を再実行しない。
SQLiteは外部操作と原子化しない。結果不明はBLOCKED、permission_requiredは既存Goal承認待ち、
再起動時のRUNNINGは既存の人間向けreconcileが必要。承認解除や外部操作の再実行を起動時に行わない。

Stage状態: TODO/STALE/REPAIRING → RUNNING → host検証 → VERIFIED または REPAIRING / BLOCKED。
Goal completion phase VERIFYING中に独立検証する。DECOMPOSEDは全子がVERIFIED後に親自身を再検証する。
StageのVERIFIEDはGoal全体の検証を置き換えない。後工程が前工程を壊すとSTALEを依存へ伝播して停止する。

修復は既存GoalRepairDiagnosisのrepairable理由だけに限定する。max-repairs既定2、設定範囲0..5。
各試行の観測（理由・成果SHA・依存版）が2回同じ場合はstage_repair_stalledで停止する。
別の成果へ変化していても上限でstage_repair_limitに停止。Goal予算を補充しない。

## Phase 4 — 計画変更

独立検証で失敗した工程の修復Runから proposeGoalStageSplit を呼べる。
これは既存Runにおける通常LLM呼出しの結果で、Goalの共有call/token予約内で生成する。
追加のLLM検証は行わず、決定的validatorを使うため検証のLLM費用は0。
案の保存だけでは計画を変更しない。そのRunのhost検証がUNSATISFIEDだった場合だけ、
同じGoal claimとplanVersionで再検証して子を追加する。UNKNOWN/REJECTEDでは採用しない。
初期の修復を経ない自動分割、親条件の書換え・削除、必須Goal要件の削除を拒否する。
最大32工程、1案8子、親深さ3、計画version最大5。既存兄弟・失敗履歴・証拠を保持する。
計画変更時は以前のVERIFIEDをSTALEにし、再確認してから依存工程を解放する。
子が全て完了しても親自身の条件が未達なら停止する。

Stage計画の登録後はGoal全体のcompletion定義も凍結する。条件緩和の自動承認機構は追加せず、
緩和自体を拒否する保守的な初期仕様。必要なら人間が別の明示Goalを定義する。
Phase 4の工程/HTTP/Tools/Policy回帰62件成功。

## API / CLI

既存Goalを作成し、最初のrunより前に工程を登録する（GoalとStageの目的・条件は別）。

```text
GET  /api/v1/projects/{project}/goals/{goal}/stages
PUT  /api/v1/projects/{project}/goals/{goal}/stages
     {"expectedPlanVersion":0,"stages":[...]}
POST /api/v1/projects/{project}/goals/{goal}/stages/{stage}/evidence
     {"expectedVersion":2,"proof":{"review":{"id":"...","sha256":"..."},"artifacts":[]}}
```

Stage形式の例（固定2工程）:

```json
[
  {"id":"draft","objective":"本文を作成","criteria":[{"relativeFile":"draft.json","jsonPointer":"/ready","expectedJson":"true"}],"dependsOnStageIds":[],"requirementIds":[]},
  {"id":"check","objective":"成果を検査","criteria":[{"relativeFile":"checks.json","jsonPointer":"/passed","expectedJson":"true"}],"dependsOnStageIds":["draft"],"requirementIds":[]}
]
```

Goalに必須のnamed requirementがある場合は、StageのrequirementIdsにその全IDを割り当てる。
条件はSHAまたは既存JSON scalar/predicateで、任意コードは実行しない。
completionを指定する場合は既存Goal Completion Gateと同じ定義・保存Review/Test/Artifactのproof形式。
JSON predicateのfeature flagは既存設定に従う。

```text
/goal stages <goal-id> --plan-version 0 --stages-json '<上記配列>'
/goal run <goal-id>
/goal progress <goal-id>
/goal stages <goal-id>
/goal stage-evidence <goal-id> --stage-id draft --stage-version 2 --proof-json '<proof>'
/goal reconcile <goal-id> --run-id <保存Run ID> --acknowledge-uncertain-side-effects
/goal run <goal-id>
```

計画変更と証拠添付は停止中かつ所有Sessionを選択して行う。HTTPは既存の認証・Project制約を使う。
completion-progressの既存フィールドを維持しstageProgressを追加。工程状態・証拠・無効化理由・
試行/修復履歴・計画履歴・残Run/call/token・停止理由・再開操作を取得できる。
Goal.updatedイベントには小さい工程状態一覧と証拠参照を追加し、詳細履歴はprogress APIで取得する。

## Phase 5 / 最終検証

評価と実モデル接続状況は [Goal Stage 評価](goal-stage-evaluation.md) を参照。
追加安全性テストで、不明結果の証拠添付による再実行承認を拒否し、不正な親参照を検証エラーにする。
Stage付きGoalの完了後も証拠変更でSTALE・Goal BLOCKEDへ戻し、残予算内で明示再開できる。
キャンセルはGoalとStage Attemptを同じtransactionで閉じる。
修復観測には条件充足数/総条件数を保存し、同じ観測の反復と条件改善を区別できる。
`rei.goal.stage.allow-split` と `rei.goal.stage.max-repairs` で4方式を切り替える。
