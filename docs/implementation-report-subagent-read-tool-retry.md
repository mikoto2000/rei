# SubAgent 読み取り Tool retry 実装記録

C15のTool障害retryへ、既定0・最大3・invocation共用の限定再試行を追加した。Policy AUTO_APPROVEかつREAD/NETWORK_READのみのToolで、型付きDB一時障害または限定された接続timeoutを再試行する。成功済みTool・単発/継承承認・書き込み分類・返却エラー文字列を再生しない。

未実装APIのcompile Red→初期3テストGreen→8新規テスト・関連PASS。共有上限、承認非再利用、ネットワーク例外分類、取消/権限の再確認、設定境界/旧constructor、実embedding呼出がSQLite Goal呼出予算を共有することを検証した。Tool workerへ同じModelCallBudgetを渡し、補助モデルの再試行費用を親/ownerless予算へ計上する。

全体回帰は3275 tests / 609 suites、failure/error/skip各0。feature c014b5ddをCommit/Pushしmain 3eca76c3へMerge済み。Merge後関連テストPASS、main Push済み。Java/configのみでNative/React変更なし。

[対象例外・費用予算・固定履歴・境界](subagent-read-tool-retry.md)。永続resume・書き込みTool retryは本機能で実装したとは扱わない。
