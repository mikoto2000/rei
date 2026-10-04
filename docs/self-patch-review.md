# Self Patch Review Cycle

CHATのselfReviewPatchは、明示されたtestCommandで作業ツリーの検証サイクルを行う。
request={testCommand,timeoutSeconds}。commandは1〜4096文字、timeoutは各1〜60秒（既定30）。
任意commandを最大2回実行するToolなので、runCommandと同じ標準全能力分類を維持する。
権限設定を迂回せず、READ専用にもSubAgent許可対象にも追加していない。
独自のLLM・External review・scheduler・DB・編集機構は作らない。

## サイクルと完了条件

1. Git root/HEADとpatch snapshotを取得する。patchなし、除外/不完全なsnapshotならtestを実行しない。
2. 初回testCommandを実行する。exit=0、timeoutなし、ログ切り詰めなしを必要とする。
3. snapshotが同一であることを確認して、Git diff --checkと未追跡テキストの静的チェックを行う。
4. findingがあればFIX_REQUIREDとして既存会話ループへ返す。既存の編集Toolで修正した後、全サイクルを再実行する。
5. review後も同じpatchであることを確認し、同じtestCommandを最終実行する。
6. 最終testの成功とsnapshot一致・残予算を確認した場合だけVERIFIED_CHECKSを返す。

初回/最終test失敗、timeout、log省略、review不完全、途中のpatch変更は完了扱いにしない。
キャンセルは既存RunCancellationの制御フローとして伝播する。
結果は初回/最終testの終了観測とC20 diagnosis、finding(path/line/kind)、前後version、次の固定手順を持つ。
command文字列、ソース本文、raw diff/logは診断結果に再掲しない。
既存RawToolResultの保存・context圧縮を利用する。

## Review範囲と上限

対象はGitのrepo rootでHEAD commitがある場合。HEADに対する現在の作業ツリーpatchを確認する。
HEAD・staged/unstaged diff・変更path・未追跡を含む現内容のSHA-256で同一性を検証する。
stage変更も検出するが、indexだけをcheckoutしてtestする機構やcommit/merge gateではない。
静的reviewはGitが報告するwhitespace/conflict markerと未追跡の同種問題を対象とする。
意味的review、API整合性、testCommandが適切なtestを実行したかは検証しない。
成功状態はチェック時点の観測であり、その後変更したら再検証が必要。

stage間で180秒の共有予算を確認する。各Git commandは最大5秒、testは指定timeout以内。
既存のowned process runnerで出力を並行回収し、timeout/cancel時は管理したprocess treeを終了する。
ファイル128件、各256KiB、現内容合計2MiB。staged/unstaged Git diffは各1MiB、test/checkログは64KiBまで。
findingは24件まで。切り詰めや非UTF8/binary・除外/unsafe pathは不完全として完了を拒否する。
Git pager・optional lock・fsmonitor・外部diff/textconv・rename検出を無効にする。
Git indexへintent-to-add等は行わず、未追跡ファイルはbounded読取で検査する。
Pathの秘密名除外はRepository Mapの既存基準を再利用する。

## 検証と残件

TDDで未実装compile失敗を確認し、順序・初回/最終失敗・変更検出・finding/不完全・cancelを検証。
実Gitの修正後再検証、stage/削除、未追跡、index不変、制限、外部helper抑止を検証。
実Shellを2回実行した記録、timeoutとログ上限も確認する。実LLMや外部Codexは呼ばない。

C21は明示起動の静的検証サイクルとして実装。意味的自己review、自動修正・修復回数管理、
すべてのChat/Goalの完了判定への強制統合、永続resume/UIはDeferred。

全体回帰: full profile 2834 tests / 544 suites、failure/error/skip 0。
