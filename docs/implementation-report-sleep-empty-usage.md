# Sleep累積予算の非正値usage修正

累積token上限だけを有効にしたSleepでは、providerの報告値0を既知の無料応答として扱っていた。既存OutputLimitRunBudgetと同じくnull／0／負値を未知usageとし、永続unknown記録と応答採用前の停止の両方を修正した。

実SQLiteで0報告後の再起動・再予約、および実Sleep＋mock ChatModelで0報告後の記憶保存抑止・再起動後モデル非呼出・予算無効時の既定互換をテストした。修正前は2 failures / 0 errors、修正後Green。全体回帰3174 tests / 599 suites、failure/error/skip各0。

feature `cdbcdd85` をPush、main `1b1404b6` へMerge。Merge後のSleepPersistentBudgetTest / SleepModelBudgetTest / SleepServiceTestはPASS、main Push済み。JavaのみでNative/Reactは再実行していない。
