# 並列 Codex レビュー 実装記録

C17の並列delegationを、既存Codex read-only REVIEWの独立batchとして追加した。最大4件・worker2・queue2・受付batch1・100ms〜120秒deadline。実ユーザーの明示parallel依頼＋管理設定opt-inを照合し、一つの親Run外部委譲権と親Run/Goal費用予算を共有する。

未実装APIのcompile Red→3テストGreen→開始済みreviewId欠落のbehavior Red→履歴修正Green。部分失敗/所有者/SQLite終端event、入力一括検査、受付競合、別workerの予算停止、取消とqueued非実行、mock native CLIのJSONL usage累積、未報告期限時の未知usage停止、設定範囲、Tool登録と実Run引継ぎを追加検証した。実CLI・外部モデルを呼ばない。

全体回帰3311 tests / 613 suites、failure/error/skip各0。新規13テストPASS。Commit/Push/Merge・Merge後確認は後続記録へ反映する。Java/configのみ変更、Native/Reactコード変更なし。

[認可・共有費用・deadline・保存結果・限界](parallel-codex-reviews.md)。独立結果の正しさ/一致を自動判定せず、複数vendor adapterや直接書込を本機能の完了に含めない。

