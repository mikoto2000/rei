# Goal予算のSubAgent継承 実装レポート

Goalの永続LLM呼び出し予約を、ホストのRunExecutionContext / ToolContext経由でdelegateTask / delegateTasksへ渡す。子の初回呼び出し・各Tool cycle・validation repairで、モデル呼び出し前に同じ予約を消費する。並列workerも同一予約を使う。

予算拒否ではモデルを呼ばずFAILED / SHARED_LLM_BUDGET_EXHAUSTEDを返す。失敗・キャンセルでも予約を返却せず、元validation履歴を保持する。子のmaxSteps/timeout/Tool policy/再帰禁止は維持する。Goal予算がない通常Chat/手動委譲は従来の制限とAPIを維持する。

Tool JSON schemaには予算・ToolContextを公開しない。直接callbackを呼ぶ場合は呼び出しIDを補い、Springの非空ToolContext要件を満たす。親履歴・Working Setを子に渡さない。

TDD: 未実装APIのcompile failureでRed確認。追加6テストで呼び出し前の拒否、repair履歴/制限、実際のChat→delegateTask→モデルとSQLiteGoal予約、並列の残1回競合、各Tool cycle、delegateTasksのToolContext/SQLite共有を確認。既存callback互換テストも成功。全体 -Pfull test は2,867 tests / 548 suites、failure 0 / error 0 / skipped 0。git diff --check成功。

Skill selector / 独立Sleepなど別LLM処理への継承、Goal外の全子共通予算、共通token上限は未対応。
