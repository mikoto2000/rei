# Goal JSON scalar条件 実装記録

A2 の完了条件を、既存ファイルSHA-256から固定JSON Pointerの型付きscalarへ拡張した。最大16条件の全一致、同一ファイル別Pointer、SHAとの混在、SQLite additive migration、再起動、既存予算・所有者・取消を維持する。strict UTF-8/JSON・64 KiB・深さ32・重複／末尾拒否、数値精度とnull／未存在を独立照合する。既存CLI／Planning prompt／HTTP／Native確認／Reflectionへ接続した。

初期compile Red→SQLite／照合Green→Planning LoopがJSON不一致をBLOCKEDにする1 failure / 0 errors、NativeがPointerを表示から落とすRedを確認→修正Green。境界検証では割り込みが不正JSONと扱われるRedを修正し、CLI fixtureのUUID契約も合わせた。新規Java10テストとNative1テストで、実DB・CLI・Planning Loop・Reflection・HTTP Native表示・型／精度／安全境界を検証した。

関連JavaテストPASS、Native全回帰93 tests PASS、React GoalControls 4 tests PASS。Java全体回帰3219 tests / 603 suites、failure/error/skip各0。feature `3dc90518` をPush、main `2d58f772` へMerge。Merge後のJsonFileGoalTest / MultiFileGoalTest / GoalRepositoryTest / GoalLoopServiceTest / GoalChatGatewayTest / GoalRecoveryTest / GoalTokenBudgetTest / GoalReflectionServiceTest / GoalControllerTest / GoalHttpTestとNative goals 5 testsはPASS、main Push済み。

[入力形式・保存／検証／継続・Reflection互換](goal-json-scalar-criteria.md)。任意codeや汎用外部条件などは別の追加候補。
