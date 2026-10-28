# ロングランタスク実行基盤の実装記録

設計と不変条件は [実行基盤](durable-task-execution.md) を参照。

## Phase 0

- 基準main: `f27def79`。専用worktree: `.worktrees/long-run-phase0`。
- Red: LongRunBaselineTest を先に追加し、未実装の LongRunFixture により testCompile が失敗することを確認。
- Green: 分離した一時SQLite fixtureを追加。中央範囲の穴・確定結果の再読込・再送・取消・版変更を検証。
- 生産コードと利用者データの変更なし。既存の実行器、Goal、Run、Checkpoint、台帳を棚卸し。
- 全Phaseの完了は未達。Phase 1〜5の受入条件・障害試験・性能比較条件を設計文書へ記録。
- PR / CI / マージ / worktree削除: 未実施。

## 再開時の手順

1. GitHubのPRとorigin/mainを確認し、マージ済みPhaseを再実装しない。
2. 作業中ブランチ、コミット、CI状態を確認する。
3. 次の未完了受入テストからRed/Greenを継続する。
4. CI成功後だけマージし、作成したworktreeを通常削除する。
