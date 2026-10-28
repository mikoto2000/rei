# ロングランタスク実行基盤の実装記録

設計と不変条件は [実行基盤](durable-task-execution.md) を参照。

## Phase 0

- 基準main: `f27def79`。専用worktree: `.worktrees/long-run-phase0`。
- Red: LongRunBaselineTest を先に追加し、未実装の LongRunFixture により testCompile が失敗することを確認。
- Green: 分離した一時SQLite fixtureを追加。中央範囲の穴・確定結果の再読込・再送・取消・版変更を検証。
- 生産コードと利用者データの変更なし。既存の実行器、Goal、Run、Checkpoint、台帳を棚卸し。
- 全Phaseの完了は未達。Phase 1〜5の受入条件・障害試験・性能比較条件を設計文書へ記録。
- コミット: `0f88f065`。PR: [#165](https://github.com/mikoto2000/rei/pull/165)。マージ: `c149ba44`。
- Java regression / Java static analysis のCI成功を確認してマージ。
- ローカル全回帰: 6,665件、failure/error 0、skip 8、14分38秒。関連7件も成功。
- ローカルの初回全回帰は一時領域がGit配下にあるため検索fixtureが親Gitを拾い中断。
  リポジトリ外の一時領域で全件再実行して成功。今後の全回帰も外部一時領域を使う。
- 専用worktreeとマージ済みローカルブランチを通常削除。既存の別worktree・未追跡ファイルは保持。

## Phase 1（CI確認前）

- 基準main: `c149ba44`。専用worktree: `.worktrees/long-run-phase1`。
- 最初のRed: TaskSnapshotTestを追加しDurableTaskStore未実装で失敗。
- Green: 列挙中断・保存窓口再生成・内容の不変性・他Project拒否・空集合・容量上限の2件成功。
- コミット: `5d1279f8`。既存schema19へ追加移行20を導入。旧レビュー/Checkpointの形式は維持。
- `801334cf`: 10,001ファイル、30階層、ページ欠落・重複、アクセス拒否のRed/Green。関連4件成功。
- `4f3eea7b`: Git tree/blobを直接読む固定コミットSnapshot。export-ignoreの漏れを防止し、未コミットモードと区別。関連5件成功。
- `6b748f43`: 保存カーソルより前への追加で漏れるRedを確認。ディレクトリfingerprintとVALIDATING段階の再開可能な照合で拒否。関連6件成功。
- `df97e815`: 全件レビューの明示Tool入口・Runとの原子的関連・Run終了ガード。既存小規模レビューを含む5件成功。
- Goalのホスト照合と列挙進捗の永続重複排除を接続。関連18件成功。列挙をレビュー成果と数えない。
- アーキテクチャの新規配線は、Phase 1で依頼されたGoal連携のための5クラス辺（ContextCompressionConfigurationからDurableTaskStoreとそのScope、FileGoalVerifier、GoalRepositoryとそのGoal）。既存のC-wiringに該当する3辺とDの5辺だけを個別に記録。処理本体の循環、UI依存、core集中の予算は拡大しない。全レポートのコピーによる予算更新は行わない。
- 最終関連検証17件成功。Goal/進捗関連18件成功。通常起動、Checkpoint、Goal/Stage、子エージェント、モデルTool登録、既存レビュー再開の関連98件成功。
- Checkstyle 0件、SpotBugsは既存1,860件に完全一致し新規0件。静的解析baselineの更新・抑制追加なし。
- Git/非Git、未コミット内容、10,001ファイル、深い階層、空集合、容量上限、権限不足、列挙中断、ディレクトリ変更、既存レビュー互換をモデルなしで検証。
- 全回帰CIと静的解析CIを確認してからマージする。Phase 2の確定結果提出・Checkpointなしの実行再開はまだ未実装。

## 再開時の手順

1. GitHubのPRとorigin/mainを確認し、マージ済みPhaseを再実装しない。
2. 作業中ブランチ、コミット、CI状態を確認する。
3. 次の未完了受入テストからRed/Greenを継続する。
4. CI成功後だけマージし、作成したworktreeを通常削除する。
