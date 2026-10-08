# 作業完遂能力の評価（Phase 0）

## 対象と既存機構

基準 revision は `cd29f4e7f1c3d5e5f361a0fec576f8bed821fff0`（Web 検索 Phase 0〜5 のマージ後）。
`GoalLoopService` は既に成功応答後に `FileGoalVerifier` を呼び、未達なら許可された理由だけを
有限の Run / LLM 予算内で継続する。`GoalCompletionGate` は SHA・現在 patch・テスト report・
Artifact・JSON predicate を照合する。`GoalRepository.reconcile` は再起動後の claim を失効させる。
Checkpoint / Scheduler / Permission / Artifact の既存機構と永続形式を変更しない。

`GoalCompletionEvaluation` は実行ループを持たず、観測値と独立条件から評価する純粋な評価器。
Spring Bean として通常チャットに挿入せず、通常の Goal や権限設定を変更しない。
条件の証拠は evaluator を呼ぶ側の独立 oracle が収集する。モデルの自己申告を condition に変換してはならない。
既存の `FileGoalVerifier` による SHA と JSON Pointer をベースラインの oracle として再利用する。

## 再現方法

JDK 25 と既存 Maven Wrapper を使用する。外部モデル・ネットワーク・ユーザー DB は使わない。

```powershell
./mvnw.cmd -Pfull '-Dtest=GoalCompletionEvaluationTest,GoalCompletionBaselineTest' test
```

`target/goal-completion-baseline.json` に schema version、基準 revision、範囲、制約、各条件の結果、
各シナリオの観測、集計を保存する。保存先は `-Drei.evaluation.output=...` で明示変更できる。
テストの一時フォルダーを SQLite / ファイル保存先に使い、Goal ID や絶対一時パスは結果に含めない。
経過時間は環境依存なので、同じ JDK・設定・fixture・マシンで比較する。報告内の revision は
改善前コードの基準であり、評価器追加コミットの revision とは別。

## 10シナリオと検証範囲

| ID | 実行／独立検証 | 制約・既存実境界回帰 |
|---|---|---|
| multi-requirement | 実 Goal Loop、SHA と JSON 必須条件 | Scripted gateway |
| test-repair | JSON テスト結果 false→true、2 attempt | 修復は fixture が決定、モデル修復ではない |
| long-job | RUNNING の間 dispatch 1回、遅延 callback 後に検証 | 外部 job は double。実プロセスは ExternalAgentProcessRunnerTest |
| approval-resume | WAITING_APPROVAL で停止、明示再開で条件成立 | 承認 UI の E2E ではない。ToolPermissionPolicyTest を全体回帰で実行 |
| restart-resume | SQLite 再構築、reconcile、旧 claim / late callback の拒否 | アプリ全体は CheckpointRestartSmokeTest |
| artifact-delivery | 保存ファイルと JSON、受渡し証拠なしを未達 | 実 Artifact は ArtifactStoreTest / ArtifactHttpIntegrationTest |
| partial-required | 1条件だけ成立、有限予算で BLOCKED | 全条件の oracle を個別実行 |
| duplicate-operation | 既存成果から検証、gateway 呼出し0 | 非冪等外部 API の exactly-once は測定しない |
| web-research | 検索を含む作業の gateway double、保存根拠の検証 | WebResearchPipelineTest 等の既存 fixture は全体回帰 |
| impossible | 不成立のまま3回上限で BLOCKED | 到達不能性は fixture 定義。モデル推論能力ではない |

## 指標の定義

- 検証済み完遂率：実行が終了し、独立 oracle の必須条件が全成立したケース / 全ケース。
- 誤完了報告率：完了を申告したのに必須条件が未達のケース / 全ケース。
  この baseline の申告は gateway 応答であり、ユーザー向け最終報告の誤り率ではない。
- 未達終了率：終了し、必須条件が未達のケース / 全ケース。待機中は終了に数えない。
- 修復成功率：成功した修復 / 実際に試みた修復。0 attempt は未取得扱い。
- 人間介入・不要反復・二重実行・権限逸脱：adapter の明示カウンター。
  基準 fixture の権限逸脱・非冪等外部操作の重複0は実環境の安全保証を意味しない。
- 経過時間：シナリオの実行開始から最終 oracle 前までの wall duration（monotonic clock）。
- 呼出し：baseline では実 Goal の LLM 予約消費。provider 呼出し回数ではない。
- tokens：モデル未使用なので未取得。空欄を0に変換せず、理由を保存する。

集計は Result のフラグを再計算し、作成済み／JSON の成功フラグだけを信用しない。
必須条件なし、重複ID、負の数、修復成功数が試行数より多い入力を拒否する。
一部ケースで計測不能なら、その指標の合計も未取得にする。任意条件の不成立は完遂を妨げない。

## ベースラインの解釈

保存する raw JSON は scripted adapter に対する再現可能な回帰基準。
その率から実 LLM の作業完遂率・Web 全体の検索品質・修復能力向上を主張しない。
後続 Phase でも同じ fixture を維持し、新しい条件を追加する場合は別 dataset / version として比較する。
live provider・実 approval UI・長時間 external job・非冪等外部 API の実評価は未取得であり、
資格情報、明示した副作用範囲、実利用の observation adapter が必要。

不要反復の baseline は、連続 attempt 後の必須ファイル SHA / 存在状態が同じで、全必須条件が
未達の場合を数える。partial-required と impossible の各2回、計4回。これは外部 job の待機や
Web の有用性を判定する production stagnation の置換ではない。
