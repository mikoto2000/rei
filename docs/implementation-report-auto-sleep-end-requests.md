# 終了時Auto Sleep要求 実装記録

A1 の終了triggerへ、明示Session終了とgracefulアプリ終了の永続要求を追加した。終了時はモデルを呼ばず、最大256件のSQLite要求をcoalesceし、現在有効なcause／登録Project／idle gateから既存workerへ渡す。終了要求は1turnから処理し、未来cronを待たない。既存budget／checkpoint／取消／retryを維持し、完了はrevision CASで削除する。Shell／認証済みHTTPの終了入口、Project限定の要求確認／revision指定取消に対応した。

未実装constructor／APIのcompile Red→実SQLite／履歴再起動のGreen→関連・境界検証を行った。fixtureの候補出典を実turn IDに合わせ、再stubはdoAnswer、取消は実providerと同じCancellationExceptionへ修正した。新規11テストで、終了時のモデル非接触、再起動後の短い履歴・cron前の処理、並行容量制限・所有者・coalesce／revision、実Shell／HTTP境界、保存失敗時の選択保持、失敗／取消の要求保持とcheckpoint非更新、busy／minimum-idle、既定互換・binding・管理CLIを検証した。

関連テストPASS。初回全体回帰はCLIサブコマンド一覧の期待値にendが含まれない1 failure / 0 errorsを検出し、契約テストを更新して関連Greenを確認した。再全体回帰は3230 tests / 604 suites、failure/error/skip各0。feature 4d3e7843をCommit/Push、main c7f34136へMerge済み。Merge後関連テストPASS、main Push済み。Java/configのみでNative/React変更なし。

[終了操作・保存／idle処理・hard kill・要求取消の範囲](auto-sleep-end-requests.md)。その他の監査表の残件は引き続き対応対象。
