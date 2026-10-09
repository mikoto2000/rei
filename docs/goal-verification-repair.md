# Goal 検証失敗からの修復（Phase 3）

明示的な completion definition を持つ Goal では、既存 GoalLoop → Project FIFO → ChatExecutionService → Planning Loop に修復を接続する。通常のチャットへ Goal を強制せず、既存の Goal / Run / SubAgent の総予算・最大反復数・Permission を維持する。

## 判定と再検証

GoalRepairDiagnosis はホストで観測した理由を TEST_FAILURE / IMPLEMENTATION_INCONSISTENCY / REQUIREMENT_UNMET / TRANSIENT_EXTERNAL / APPROVAL_WAIT / UNKNOWN_RESULT / UNREPAIRABLE / BUDGET_INSUFFICIENT に分類する。保存済み Self Patch Review の INITIAL_TEST_FAILED / FINAL_TEST_FAILED は所有者を照合してからテスト失敗と判定する。未知の結果、壊れた証拠、未知predicate、承認待ち、禁止された操作は自動修復しない。HTTP の型付き timeout / network error は一時障害として表示するが、無条件の更新再送はしない。

既知の未達は現在のファイル・SHA・必須条件を再観測し、修復診断と未達一覧を次の既存 planner に渡す。既存 task / action plan、reviewPatchRequirements、テスト診断、SubAgent の retry / repair を使える。新しい実行エンジンや人間限定 DiagnosedRepair の自動適用は追加しない。人間の条件や test command をモデルが弱めることはできない。修復後も FileGoalVerifier / Completion Gate が現在の証拠を独立に検証する。

/goal progress と HTTP completion-progress の追加 diagnosis に、分類・修復可能性・人間の判断要否・理由を返す。旧 constructor / JSON は維持する。FAILED の未知実行結果を、ファイル未達というだけで修復可能へ置き換えない。

## 予算

rei.goal.repair-reserve-calls=2（0..10）を既定とする。明示definitionを持ち次の Goal Run が残る最初の実行で、同じ永続 LLM 予約から最大2呼び出しを後続修復へ残す。最低1呼び出しは最初の実行へ渡す。REPAIRING は残った同じ予算を使用する。増額・補充はしない。0なら予約なし。

ChatExecutionResult の追加 stopCode で既知の LLM 呼び出し上限と未知の実行失敗を区別する。予約境界の停止では独立検証を行い、既知の修復可能な未達だけを続行する。総上限到達でも証拠が揃っていれば検証完了できるが、未達なら停止する。途中で上限へ達したという一般的な失敗文字列だけでは再実行しない。

token 使用量は同じ Goal reservation へそのまま報告する。pending / unknown / overshoot の使用量を、ファイル一致だけで成功に隠さない。provider の将来の token 消費を予見する保証や、外部操作の exactly-once 保証は追加しない。取消・Permission・既存 Checkpoint の不明操作ガードが優先する。

## 検証と評価の範囲

型追加の compile Red と、予約されない4呼び出し・未確定tokenでの誤完了・検証停止・テスト分類の実行 Red を確認後に Green 化した。実 SQLite の予約、token転送、取消、未知結果、型付き上限、実ファイルと保存JUnit証拠、既存 gateway の修復promptを検証する。

Phase 0 の旧 scripted gateway 比較は70%完遂 / 30%自己申告の誤完了 / 30%未達終了 / 修復1/1 / 介入2 / 不要反復4 / 重複0 / 権限逸脱0 / 予約15のまま。旧fixtureには新しい予算sliceやライブモデルの修復能力を測る用途がない。新機能は別の回帰テストで検証し、ライブモデル改善率とは報告しない。token は引き続き scripted gateway のため取得不可。