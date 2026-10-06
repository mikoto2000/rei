# 外部レビューの保存と明示再レビュー

外部Codexレビューを開始する前に、既存SQLiteへレビューID、Project ID/canonical root、Session/Run ID、対象の相対パス、開始時刻を保存する。終了時に構造化結果・完了時刻を一度だけ更新する。返却結果のreviewIdから記録を参照できる。保存結果は外部観測であり、承認・Memory・検証済み事実には昇格しない。

CHAT Tool:

- `listCodexReviews`: 現在のProject/rootの直近20記録を読む。外部processを起動しない。
- `getCodexReview(reviewId)`: 同じProject/rootの1記録を読む。
- `requestCodexReReview(previousReviewId, task, context?)`: 保存済みレビューを参照して、同じ対象を新しくread-onlyでレビューする。

再レビューにはそのRunのユーザー入力による明示的なCodex依頼が必要。過去の許可や結果は実行を認可しない。通常レビューと同じRunごと1回の予算を消費する。前回記録はterminalで、同じProject/canonical rootに所属する必要がある。保存対象を現在のfilesystemで再検査し、削除やProject外への脱出を拒否する。前回の結果は最大5000文字のuntrusted contextとして渡し、現ファイルでの再検証を指示する。修正済み・解決済みと自動判定しない。

保存結果には既存CredentialRedactorを適用する。summaryは2048文字、findingsは24件（title256、reason/recommendation各1024、location512文字）、warningsは12件・各512文字。切り詰め時はwarningを付け、SUCCESSをSUCCESS_WITH_WARNINGSにする。prompt、設計context、ソース本文、stdout/stderr全文、rawOutput、CLI sessionは自動保存しない。ただし構造化された指摘に含まれる引用は結果として保持される。

開始記録を保存できない場合、外部processは開始しない。実行後の結果保存に失敗した場合、FAILEDを返し、記録はSTARTEDのまま残す。STARTEDは現在の実行中を保証せず、クラッシュや保存障害で結果不明になった可能性がある。自動resume/retry・再レビュー参照は拒否する。ユーザーの新しい明示依頼による通常レビューは別記録になる。キャンセル結果は保存後に既存Runのキャンセルへ伝播する。

`/agent codex review [target]` の既存動作にも保存を適用する。新しい履歴・再レビューはCHAT Tool経由で利用でき、専用slash構文は追加していない。Codexのephemeral/read-only permission profileとtimeoutを維持する。CLIの安全capabilityが不足する場合の拒否も維持する。

後続変更でopt-inのCLI session resumeを追加した。設定・保存境界・二重継続防止は [external-review-continuation.md](external-review-continuation.md) を参照。既定では引き続き一時レビューである。fix/implementationの書き込み委譲、commit/push、他agent、外部並列、比較結果の自動確定、履歴削除UIは未実装。
