# Goal Stage 評価

開始main: `c6da942a277fc47a827e4ed9f37fc7b850227c23`。JDK 25 / Maven Wrapper 3.9.14 / Windows。
生データは [48ケースの観測](evaluations/goal-stage-scripted.json)。実モデルは未使用。

## 再現

```powershell
./mvnw.cmd -B -Pfull '-Dtest=GoalStageEvaluationTest' test
```

`target/goal-stage-evaluation.json` に4方式×12ケースの結果と集計を出力する。
同じ最終Goal条件（a.jsonとb.jsonのok=true）、6 Run / 6 call（予算枯渇ケースのみ2 call）、
同じ一時filesystem/SQLite、同じscripted gatewayと独立FileGoalVerifierを使う。
各方式の差はStage有無、max-repairs、allow-splitだけ。プロバイダー呼出し・token実測はしない。

| 方式 | 検証済み完遂 | 誤COMPLETED | 再開成功 | 模擬操作重複 | 人間介入 | Run | call予約 |
|---|---:|---:|---:|---:|---:|---:|---:|
| 既存Loop | 4/12 | 0/12 | 2/2 | 12 | 2 | 41 | 41 |
| Stageのみ | 3/12 | 0/12 | 2/2 | 0 | 2 | 20 | 20 |
| Stage＋修復 | 4/12 | 0/12 | 2/2 | 0 | 2 | 27 | 27 |
| Stage＋修復＋再分割 | 5/12 | 0/12 | 2/2 | 0 | 2 | 30 | 29 |

実行時間はraw JSONのelapsedNanos。JIT・cache・実行順の影響があり性能改善率を主張しない。
callは実LLM呼出しではなくGoal予約数。tokens=null（実モデル未使用で取得不可）。
人間介入は明示reconcileの回数。重複は確認済みとした模擬操作の再実行数で、実外部APIのexactly-once保証ではない。
誤COMPLETEDはホストのCOMPLETEDかつ独立oracle未達の割合であり、既存評価の「応答文中の完了申告率」と区別する。
Stageのみでは修復が無いため失敗fixtureを完遂できない。再分割の改善はscripted提案が解けるケースを含む結果で、
実モデルの提案能力や長時間タスクの改善率の証明ではない。

## ケース

要求の10ケース: normal / intermediate-failure / damaged-predecessor / process-exit /
unknown-effect / repair-budget / parent-unmet / false-done / weakened-plan / duplicate-resume。
追加でrepair-recoveryとdecomposition-recoveryを比較する。
中間実行失敗・不明外部副作用は停止、偽DONEは独立検証で拒否、子のみ成功では親とGoalは未達を維持する。
process-exitはcallbackを保留してRepositoryを再構築し、exact Runをreconcileして再開する。
実プロセス終了の既存checkpoint smokeは全回帰で実施する。新Stageのcommit直後終了はGoalStageSafetyTestでも直接検証する。

## 実モデル評価手順

2026-10-18の接続確認: 環境のOpenAIキーは公式APIのモデル一覧でHTTP 401。
リポジトリ既定ローカルモデルendpointも5秒以内に接続できなかった。実モデル評価は未実施。
資格情報・本文は出力せず、使用不能なモデルで成功率を捏造しない。

1. 到達可能な同一プロバイダー・固定model/versionを設定する。
   `REI_OPENAI_BASE_URL` / `REI_OPENAI_API_KEY` / `REI_OPENAI_CHAT_MODEL` を使い、全方式で同じ設定にする。
2. 通常データから分離したREI_DATA_DIRと、変更可能な一時Projectを方式/ケースごとに新規作成する。
   tool permissionを有効にし、既存web認証を設定してReiを起動する。
3. 既存APIで `POST /api/v1/projects/register {path}`、`POST /api/v1/sessions {projectId,title}`。
   Goalは `POST /api/v1/projects/{projectId}/goals` に同じobjective/criteriaJson/maxRuns=6/maxLlmCalls=6を送る。
   token上限も `rei.llm.output-limit.max-total-tokens-per-goal` で固定する。
4. 旧方式はStage未登録。Stageのみは `rei.goal.stage.max-repairs=0,allow-split=false`。
   修復はmax-repairs=2,allow-split=false。分割はmax-repairs=2,allow-split=true。
   設定を変える際はサーバーを再起動し、計画は最初のrun前に同じ2工程をPUTする。
5. `POST .../goals/{id}/run` で既存GoalChatGateway/Chat/Planning Loopを実行する。
   上記12ケースの障害は同じタイミングで注入する（偽DONE、保留callback、テスト結果、承認、不明副作用、成果改変）。
   モデル応答からoracleを作らず、同じSHA/JSON/Test/Review/Artifact条件をホストで採取する。
   実副作用を必要とするケースはテスト専用の記録付きstub endpointを使い、利用者の外部資産を使わない。
6. terminal時のGoal、completion-progress、stages、history、実token報告、provider呼出し、wall time、
   stubの操作ID、承認/reconcile操作をJSONとして保存する。各方式/ケースを同じseed設定で複数回反復し、
   完遂率・誤完了率・再開率・重複・介入・token/call/timeと分布を比較する。
   実モデルの失敗をassertで除外したりscripted結果と合算しない。

## 予算と制約

1 Stage Attemptに1 Runを使う。親再検証もRunを消費するため、工程数が多いと既存10 Run上限で停止する。
上限を撤廃/補充せず、ユーザー指定の既存1..10 Run内で工程数と修復/分割の余裕を確保する。
最初は2〜3工程を推奨。上記分割成功fixtureは5 Run（4 call）を使う。
SQLiteと外部副作用を原子化せず、結果不明・承認待ち・使用token不明は安全に停止する。
分割案生成は既存Runの共有予算内。host validator/file検証はLLMを呼ばない。
条件緩和は未サポートで拒否。任意Stage並列実行と複数Stage/Runは将来の拡張点としてモデル上の結合を避けている。
