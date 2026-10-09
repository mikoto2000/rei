# 作業完遂能力向上の実装記録

先行 Web 検索効率化 Phase 0〜5（PR #42〜#47）の main マージ、リモート main の変更包含、最新 main の取得を確認して着手した。監視 Automation web-phase は開始時に PAUSED にした。先行 PR は代わりにマージしていない。各 Phase は前段の main マージ後に最新 origin/main から開始し、独立ブランチ / PR として残す。

| Phase | ブランチ | PR | 内容 |
|---|---|---|---|
| 0 | feature/goal-completion-evaluation | [#48](https://github.com/mikoto2000/rei/pull/48) | 独立評価器、10シナリオ、保存ベースライン |
| 1 | feature/goal-completion-gate | [#49](https://github.com/mikoto2000/rei/pull/49) | 必須/任意の名前付き条件、証拠とrevision、保存と受渡し、補助状態 |
| 2 | feature/evidence-based-progress | [#50](https://github.com/mikoto2000/rei/pull/50) | 実本文・ファイルrevisionによる進捗、Web metadataと情報の区別、bounded ledger |
| 3 | feature/goal-verification-repair | [#51](https://github.com/mikoto2000/rei/pull/51) | 失敗分類、現在の未達情報、同一予算からの修復用予約、独立再検証 |
| 4 | feature/durable-goal-resume | Phase 4 PR | 永続待機、条件再確認、既存Scheduler / Checkpoint / Goalの継続 |

## テスト・マージ

| Phase | ローカル全件 | Windows CI | merge commit |
|---|---|---|---|
| 0 | 4026 / failure 0 / error 0 / skip 1 | 37859585174：同件数成功 | d4895388dfc675016e83f7a356198f4892b4cef7 |
| 1 | 4036 / failure 0 / error 0 / skip 1 | 37865089908：同件数成功 | 9fe1e47740be1c9a77657024aeb989def59aa49c |
| 2 | 4051 / failure 0 / error 0 / skip 1 | 37867365764：同件数成功 | c5bc9ab00221eb328f72ddc71557082c725b6b48 |
| 3 | 4062 / failure 0 / error 0 / skip 1 | 37869865620：同件数成功 | db232aef9e1689f52c613efc0ae8125ec5439a5b |
| 4 | 全件検証中 | CI 確認後にマージ | Phase 4 PR 履歴で確認 |

各 Phase で compile/runtime Red → Green を確認し、既存 assertion を弱めず関連・全件の回帰を行う。skip 1 は既存 PlantUML テスト。Phase 1 の CI で発覚した並列 token 波の競合と同一ReflectionサービスのSQLite競合を修正し、元の検証を維持した。Phase 4 の関連160件、Native Goal / Schedule / Checkpoint契約、React99件とtypecheckも確認する。全件ログは worktree の target/phaseN-full.log、CI は各 PR の checks。

Phase 3 で検討したスレッドダンプとテストログの継続artifact uploadは、自動承認レビューが「機密情報を含み得る未承認の外部共有」として拒否した。追加していない。既存 CI の通常ログとローカルログで必要な検証を続行した。

## 再利用と主要変更

GoalCompletionEvaluation が、実行主体から渡された観測と独立した必須条件の証拠を採点する。GoalCompletionGate / FileGoalVerifier は既存の exact SHA、JSON Pointer / declarative predicate、保存Review / Artifactを再利用する。GoalCompletionProgress は未達・revision・受渡し・診断をread-onlyで示す。モデルが人間の条件を変更したり、受渡し確認を偽造したりできない。

ProgressEvaluator / ProgressEvidence は実本文のSHA、同じ行・内容・適用revisionの重複排除を行う。先行Web cache / result / fetch budget は変更しない。StagnationDetector と RunExecutionContext の絶対上限は維持する。新しい本文が意味的に有用かを完全に判定する仕組みではない。

GoalRepairDiagnosis / GoalRepairBudget は既存 GoalLoopService → GoalChatGateway → ChatExecutionService → Planning Loop に接続する。既存 TaskState / ActionPlan、SubAgent retry / repair、Self Patch Reviewを使い、別実行エンジンを作らない。型付き stopCode と現在の条件で既知の修復だけを続行する。未確定token使用量は停止する。

GoalWaitRepository / GoalWaitService は新しいbindingだけを保存する。既存 PersistentDependencyRepository / DependencyObservationService / DependencySourceProbe、PersistentAgentScheduler / AgentScheduleDispatcher、PersistentCheckpointService / CheckpointReconciler、Project FIFO、Run Registry、Permission、Goal/Dependency/Checkpointの通知経路を接続する。Checkpointの計画・証拠・個別操作確認・leaseを引き継ぐ。古い Run の callback と model reservation を新 Run から切り離す。RunのないDependency通知がCheckpoint listenerで失敗する境界も修正した。

## 評価指標の比較

[固定10シナリオ](goal-completion-baseline/main-cd29f4e7.json)は旧gatewayの回帰比較用に維持した。最新 main でも同じ期待値である。

| 指標 | 改善前 | 同一旧fixtureの改善後 |
|---|---:|---:|
| 独立検証済み完遂 | 70% | 70% |
| scriptedモデル自己申告の誤完了 | 30% | 30% |
| 未達を残した終了 | 30% | 30% |
| scripted修復成功 | 1/1 | 1/1 |
| 人間介入 | 2 | 2 |
| 不要反復 | 4 | 4 |
| 二重実行 / 権限逸脱 | 0 / 0 | 0 / 0 |
| Goal予約呼び出し | 15 | 15 |
| token | 取得不可 | 取得不可 |

実行時間は測定しJSONに保存するが、ファイルシステム・マシン負荷で変動する。モデル、Web、外部ジョブはダブルであり、provider tokenは存在しない。ライブモデルの完遂率・自己申告の改善率は未計測。

[追加の対比較](goal-completion-baseline/gate-independent-comparison.json)は、必須の名前付き条件 / テスト証拠 / Artifact受渡しが欠ける3ケースを、同じ基礎ファイルを使って比較する。この評価はホストの COMPLETED 判定を採点し、固定10ケースのモデル自己申告とは別指標。

| 追加3ケースの指標 | 基礎ファイルだけのGoal | 明示GateのGoal |
|---|---:|---:|
| 誤ったホスト完了 | 3/3 | 0/3 |
| 独立検証済み完遂 | 0/3 | 0/3 |
| 未達終了 | 3/3 | 3/3 |
| Goal予約呼び出し | 0 | 9 |
| 反復で証拠が増えない回数 | 0 | 6 |
| 修復成功 | 試行なし | 0/6 |
| 二重実行 / 権限逸脱 | 0 / 0 | 0 / 0 |

これは未達を成功扱いしない改善を示す。固定gatewayが成果を修復しないため呼び出しは増える。実Chatの停滞判定やライブモデルを通す実運用評価ではない。修復成功率の向上や総コスト削減を、この値から主張しない。

## 設定・現在の実行環境

コードとテストを main に反映する開発作業であり、現在稼働する「れい」を再起動・deployしない。実ユーザーDB・権限・Scheduler設定は変更しない。運用で有効にするには新しい main でビルド / 起動し、既存設定と明示Goalの定義を確認する。

- rei.tool-permission.enabled=true。承認・拒否と能力分類を維持する。
- completion definition は明示登録。require-all は既定false、通常チャットへ強制しない。
- rei.goal.repair-reserve-calls=2（0..10）：明示Gateの初回実行で同じ総予算から予約。
- 自動条件継続は rei.agent-scheduler.enabled=true と rei.dependency-watcher.enabled=true、および人間のactivation。
- rei.checkpoint.enabled=true（既定）。HTTP待機の観測にはNETWORK_READのAUTO_APPROVEが必要。
- 人間回答は実際のツール承認の代用にしない。各新Runも既存Permissionを通す。

利用と停止条件は [Gate](goal-completion-gate.md)、[進捗](stagnation-aware-execution.md)、[修復](goal-verification-repair.md)、[待機](durable-goal-resume.md)を参照する。Shell / HTTPに待機作成・版数付きresumeを追加し、SSE / Notification / Nativeの既存契約を維持する。Nativeの専用待機作成フォームは未追加。

## 残る制限と確認事項

外部 Git push / PR / API / DB / 通知の exactly-once は保証しない。保存済み不明操作は個別結果確認が必要。再接続できないプロセスは再生成しない。RESUMINGの不明受付は自動再送せず、正確なRunのreconcileと個別確認を経て待機を解除する。

検索結果やファイルの新規性は進捗候補であり、意味的有用性・要求達成の証明ではない。Goalの判定は独立Gateで行う。ライブモデル評価、実外部サービスのidempotency、実行環境のdeployment、Nativeの新規フォームは今後の運用課題である。

ユーザーは有効化時に、必須/任意、exact revision、test command、受渡し確認、予算、承認能力、待機期限、停止/再開手順を確認する。コードの存在と稼働環境での有効化を区別する。