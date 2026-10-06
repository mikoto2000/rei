# Sleep プロジェクト累積予算 実装記録

A2 の Sleep 跨ぎ費用予算へ対応した。呼出回数／報告 total tokens をプロジェクト別に SQLite へ予約・計上し、従来の1回ごとの上限と併用する。既定は無効。予約はモデル呼出前、usage 計上は解析・記憶保存前。失敗・preview・記憶 transaction rollback でも返金しない。取消や再起動で未報告の予約が残る場合は token 有効時に追加呼出を止める。生成設定・同梱設定と既存 constructor 互換を維持した。

初期 Red は未実装の永続予約／計上 API に対する compile 失敗。実 SQLite の再起動・プロジェクト分離・累積・正確な上限・上限変更・unknown／pending・8並行予約に対する上限3の検証と、実 Sleep + mock ChatModel の preview後再起動、抽出＋意味解決超過後の再起動、provider障害、取消後 pending、設定 binding を Green にした。新規10テストを含む全体回帰は3159 tests / 598 suites、failure/error/skip各0。

feature `6b8b0438` を Push、main `43f5ea06` へ Merge。Merge後の SleepPersistentBudgetTest / SleepModelBudgetTest / SleepServiceTest / AutoSleepServiceTest / ExternalConfigFileServiceTest は PASS、main Push 済み。記録更新時に生成設定テストの重複 assertion を除去し、同テストを再検証した。Java/config変更のため Native/React は再実行していない。

[適用範囲・未知usageの扱い](sleep-persistent-budget.md)。CLI／embedding／rerank、RunContextなし要約などは残件。
