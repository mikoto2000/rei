# 外部Codexレビューのセッション継続

`rei.external-agents.codex.persist-sessions=true` を明示設定すると、新規レビューから `--ephemeral` を外し、Codex CLI自身がセッションを保持する。既定値はfalseで、従来の一時レビューを維持する。CLIの保存先・保持期間・削除はCLI側の設定に従う。この設定を有効にするとCLI側にはプロンプトや読んだソースを含む会話が残り得る。ReiのSQLiteには構造化レビューと不透明なUUIDだけを保存し、CLIの会話本文や認証情報をコピーしない。

CHAT Tool `requestCodexContinueReview(previousReviewId, task, context?)` は、保存済みの成功レビューに対応するCLIセッションへ現在のレビュー依頼を追加する。引数はReiのreviewIdであり、CLI UUIDを直接指定できない。現在のユーザー入力でCodexレビューを明示的に依頼することが必要。Project ID/canonical root、対象の現在位置を再検査し、通常レビュー・新規再レビューとRunごと1回の委譲予算を共有する。ReiのSession/Run IDはCLI UUIDとは別物で、継続のために偽のRunやSessionを作らない。

UUIDは成功したCLIの `thread.started` イベントからのみ取得する。形式不正・複数の異なるUUID・出力切断・構造化結果の読取失敗・失敗やキャンセルでは継続可能と扱わない。旧保存JSONはUUIDなしとして読める。resumeはUUIDを明示し、`--last` や `--all` を使わない。継続結果にも同じUUIDが必要。失敗・セッション削除・CLI非対応時に新しいレビューへ自動切替しない。

CLIの新規実行とresume双方の隔離capabilityを事前確認する。ignore-user-config/rules・strict-config、read-only filesystem、機密パス拒否、network無効、MCP/plugins/hooks/apps/multi-agent等の無効設定、stdin入力・JSON schemaを維持する。capability確認と実レビューは同じtotal timeout内で実行し、既存Runキャンセルを伝播する。CLI保存用の内部処理はCLI自身が行い、レビュー対象への書込許可を付与しない。

同じ保存レビューからの継続はSQLiteの一意claimで一度だけ許可する。結果不明・失敗・結果保存障害でもclaimを解除せず、再起動後も再送しない。成功した子レビューから次の明示継続は可能。実行できなかった場合は、ユーザーの新しい依頼による `requestCodexReReview` で別のレビューを開始できる。CLIの履歴更新とReiのDB更新は分散transactionではないため、途中障害は成功とみなさない。

継続は読み取り専用レビューに限定する。fix/implementationの書込委譲、他provider、並列外部委譲はこの変更の対象外。slash構文は従来どおりで、継続は既存CHAT Tool経路を利用する。
