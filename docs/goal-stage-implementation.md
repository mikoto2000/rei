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
