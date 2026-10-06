# 外部レビューから修正案・明示適用・再レビューへ

既存の保存レビューとText Change Setを接続する。Codexには読み取り専用で単一ファイルの修正案を生成させ、対象ファイルの変更はReiの既存Apply経路で行う。書込sandboxを外部CLIへ付与しない。

例えば現在のユーザー入力で「Codex に保存レビューの修正案を提案して」と依頼する。CHAT Tool `requestCodexFixProposal(previousReviewId, task, context?)` は、現在Project/canonical rootにある成功済みレビューを取得し、その対象と指摘を再確認させる。モデルのtask/contextだけ、通常のレビュー依頼だけ、過去の許可、他ProjectのID、STARTED/失敗結果は修正案作成の認可にならない。

CLIは新規の隔離read-only実行として、専用prompt/JSON schemaから `proposal: null` または `{path,expectedText,replacement}` を返す。完全な元本文と置換案は各64KiB UTF-8以内。既定のephemeral、ignore-user-config/rules、機密パス除外・network/MCP/plugin無効、timeout/cancellationを維持する。元本文や置換をredact/truncateして架空の一致を作らない。不完全な出力は失敗とする。

Reiは単一ファイルのProject/root・元レビュー対象範囲・機密/リンク除外・UTF-8/byte上限・実ファイルとの完全baseline一致を検査し、共通SQLite Change SetへPROPOSEDとして保存する。ファイルは変更しない。結果のreviewId/changeSetIdは追跡用で、適用済みや検証済みを意味しない。`proposal: null` は保存Change Setなしの結果であり、自動的に修正完了と判断しない。

親は `inspectTextChangeSet(changeSetId)` でdiffを読み、根拠・要求適合・未検証範囲を独立確認してユーザーへ提示する。明示適用要求と既存Policyに従い、正確なID/proposalSha256で `applyTextChangeSet` を実行する。適用は通常の編集Event/cache更新・stale検査・一回claimを利用する。不要ならdiscardする。

修正案生成は通常レビュー/新規再レビュー/native継続とRunごと1回の外部委譲予算を共有する。適用後の再レビューは新しいRunで明示依頼し、修正案のreviewIdを `requestCodexReReview` に渡す。同じRunで委譲予算を迂回したり、結果不明の実行を自動再送したりしない。

修正案の全文は共通Change Setにだけ保存する。外部レビュー履歴や親の委譲結果にはproposal本文/raw logsを含めず、構造化結果とchangeSetIdを保持する。保存レビューとChange Setの更新は分散transactionではない。提案保存後に結果保存やキャンセルが発生した場合は自動適用せず、返却/保存されたchangeSetIdから手動で確認する。

通常の `/agent codex review [target]` の構文・補完は維持する。fix proposalはCHAT Tool経路だけに公開し、子SubAgentへの権限は拡張しない。複数ファイル修正、外部CLIによる直接書込/commit/push、他providerや並列外部委譲は追加候補。
