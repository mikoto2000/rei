# 永続 Goal / bounded Planning Loop

Status: Implemented (independent file verification slice)

Branch: `codex/persistent-goal-loop`

Commit / Merge: `feat: persist bounded goals with independent file verification` / `Merge branch codex/persistent-goal-loop` を参照。

Merged into: `main`

Implemented:
- SQLiteのGoal、試行、履歴、累積予算、原子的claim。同じProject/Sessionは同時1件。
- Project/root/Session/目的/条件を固定。Run 1..10、制御対象Chat LLM呼び出し1..100。
- 既存Project FIFO、Chat、ActionPlan、TaskState、Tool permission、checkpointを再利用。
- 独立した読み取りSHA-256検証。モデルの完了宣言だけではGoalを完了にしない。
- Chat成功でも条件不成立なら残予算内で継続。拒否・承認待ち・失敗・キャンセルでは停止。
- LLM呼び出し前の永続予約。明示再開・再起動・進捗で予算を補充しない。
- /goal create|list|show|run|verify|history|cancel、所有GoalイベントとShell表示、停止時のattention。
- queued cancel・登録済みRunキャンセル・遅延結果の非上書き。起動時の自動再実行なし。

Tests:
- Red: 未実装GoalRepositoryによるcompile failureを確認。
- Green: 永続予算、再起動、claim所有・失効、並行予約、同一Session排他、危険な条件と上限の拒否。
- 「完了」と返したRunを検証失敗にし、次のRunで実ファイルが一致した時だけCOMPLETED。
- 実際のChat Tool loopが永続LLM上限で停止し、明示再開でも追加モデル呼び出しが発生しないこと。
- 実gatewayの固定所有、queued cancel、Project移動、承認停止、ShellのProject境界。
- 前提成立時の0 LLM、サイズ・通常ファイル検証、キャンセル後の遅延完了拒否。
- Shell/Web event projection・非公開reason・attentionの回帰。
- 全 Java: 2,733 tests / 527 suites、failure=0 / error=0 / skipped=0。
- JDK25、既存cache、`mvnw.cmd -o -Dmaven.repo.local=F:\project\rei\.m2\repository -Pfull test -q`。
- diff review / `git diff --check` PASS。

Result: PASS

Remaining:
- ファイル条件以外のbuild/test/API条件、任意の目的の意味的な完了判定。
- SubAgent / Skill selector / Sleep等の独立LLM処理への予算継承。
- 不確定RUNNINGのShell復旧は後続goal-uncertain-run-recoveryで対応。Goal専用Web管理API・Native UI・介入mailboxは未対応。
- Goalとcheckpointの自動関連付け、複数アプリ間FIFO。
- 外部サービスは不要。実行は人間の明示操作と有効なTool permissionに限定する。
