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

## Phase 1

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
- 初回全回帰CIは6,674件中failure 1/error 13。現行schemaから旧schemaへ戻す既存fixtureが追加テーブルを残していたため、旧版fixtureを正しく再構成する共通helperを追加。本番移行のCREATEを緩めず、衝突拒否を維持した。
- 修正後の移行関連49件成功。schema19→20の中断・rollback・再試行、旧レビューのバイト保持、不正な既存テーブルの拒否も追加検証。全回帰CIを再実行する。
- 修正コミット `87caa7bc` の全回帰CIと静的解析CIが成功。PR [#166](https://github.com/mikoto2000/rei/pull/166) を `9e3526f2` へマージ。
- ローカル全回帰も6,676件、failure/error 0、skip 8、16分31秒で成功。
- ログ保存後にworktreeを通常削除。Gitの登録解除後に空ディレクトリが残ったため、絶対パスと空であることを確認して通常削除した。強制削除なし。マージ済みブランチも削除。
- 主checkoutには別作業のProgressEvaluator等の未コミット変更が出現し、mainのfast-forwardが拒否された。変更を保持し、後続Phaseはorigin/mainを明示して作成する。

## Phase 2（実装中）

- 基準main: `9e3526f2`。専用worktree: `.worktrees/long-run-phase2`。
- 最初のRed: 既存ReviewJobの101回再開、旧Runからの遅延書込み拒否、履歴読取り維持。
- `c5a8714c`: 上記Redを確認し、履歴を既存review_participantsへ保持、JSONの現在参加者と分離。既存Checkpointを含む49件成功。旧Runのcleanupも新しい実行を停止しない。
- `09c22b31`: 原子的なWorkItem claimとRun関連付け、凍結内容・SHA-256・UTF-16範囲の照合。関連5件成功。schema21は追加移行。
- `939b6869`: 原文の配信確認を現在のclaimへ限定し、範囲ハッシュの不一致・他Runからの確認を拒否。関連2件成功。
- `505b872a`: 結果・指摘・完了状態の同一トランザクション保存、結果本文のSHA-256、冪等な再送と競合拒否、構造的な範囲網羅判定。関連6件成功。
- `e7e0efeb`: Checkpointを参照しないTask発見と明示再開、旧Runの失効、確定結果の回収。101回の再開で完了済み項目を再実行しない。保存・完了の前後4境界にSQL障害を注入しrollbackを検証。関連3件成功。
- `cf8ed97b`: 子JVMを実際のSQLite commit直前・直後でhaltする2件の試験に成功。直前は未完了を回収、直後は確定結果1件を再利用。実LLMなし。
- `c96d02af`: 既存ReviewTools.Page・ResultDelivery・trusted receiptを使うツール接続と、TokenEstimatorに基づくUTF-16境界分割。権限・進捗・schema20→21のrollback/retry・既存レビューを含む20件成功。
- `a6f04b9b`: 実際のモデル呼出しで、旧/新レビューとも通信失敗時は配信確認しない。自然文の完了はREVIEW_PARTIALになる。モデル境界4件と既存構造テスト6件成功。
- `c6380099`: 保存本文ハッシュ・原文・引用の独立検証。破損結果を完了扱いせず、再開時にも回収せずに拒否し、元Runの状態をrollback。関連10件成功。
- `f8826c82`: 対象版・範囲だけでなく登録済み観点と必須フラグとの一致を確認する。差替え拒否のRed/Greenと実アーキテクチャの2件成功。
- 静的解析の最終結果: Checkstyle 0件、SpotBugs既存1,860件・新規0件。baseline拡大・抑制追加なし。DBファサードは状態を共有せず、既存の直列transaction portだけを共有する。
- ローカル全回帰を実行中。CI確認・PRマージ・worktree削除は未完了。Lease・永続予算・取消接続はPhase 3、規模改善はPhase 4、横断レビューはPhase 5の未完了項目。

## 再開時の手順

1. GitHubのPRとorigin/mainを確認し、マージ済みPhaseを再実装しない。
2. 作業中ブランチ、コミット、CI状態を確認する。
3. 次の未完了受入テストからRed/Greenを継続する。
4. CI成功後だけマージし、作成したworktreeを通常削除する。
