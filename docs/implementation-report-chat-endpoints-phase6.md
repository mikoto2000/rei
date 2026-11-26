# 共通チャット接続基盤 Phase 6 実装記録

Phase 6 は進行中。外部送信同意の実装と統合検証が完了するまで、通常チャットの ChatGPT Plan transport capability は未確認のままとする。以下の fixture は実サービスとの互換性確認ではない。

## 既存基盤の再利用

- Spring AI / 同梱 SDK の型付き namespace API を使い、公開されたローカル関数だけを `rei` に格納する。完成した HTTP JSON の interceptor 書き換えは行わない。
- namespace の検証とネイティブ呼び出しの照合は要求ごとに閉じる。呼び出し・結果の再送は既存のモデルメッセージ保存形式と所有者・接続先の照合を使う。
- 通常チャットの `StagnationChatModel` と既存ツール権限ガードを維持する。3 モードの正常応答・未完了応答、およびローカル callback 前の要求枠解放は SDK-intercept fixture で検証した。
- 推論送信同意はツール権限と別に扱う。送信内容の固定には既存 `ModelMessageCodec` を使い、ツール定義・確認属性・配信ポリシーを保持する。承認待ちの保存・キュー・再開は既存処理への明示的な拡張として実装する。

## 個別依存レビュー（内容固定処理）

対象は次の 1 ペアに限定する。

`dev.mikoto2000.rei.core.policy.InferenceExportRequest -> dev.mikoto2000.rei.core.contextbudget.DeliveryToolRegistration`

推論送信の内容を固定する際、callback の定義だけをコピーすると `Registered` の配信種別・レビュー属性を失う。これらは既存のファイル読み取り・配信制御に必要であり、属性を失った wrapper を使って検査を通すことはしない。既存の `preservePolicy` を再利用し、callback の実行先と ToolContext をそのまま委譲する。`StableToolDefinitions` などにも同じ保存処理への依存が既にある。

この依存を回避するための独立したツール登録機構、reflection による marker の複製、権限属性の削除は導入しない。承認のための短命な入力固定処理から既存の配信属性保持への、直接かつ限定された型依存として扱う。作業の承認範囲は、依頼された Phase 6 の Function Calling・動的ツール選択・送信権限の分離であり、このペアを Phase 6 PR のレビュー対象として明示する。

`docs/architecture/README.md` の個別レビュー手順に沿って、検査で報告されたこの 1 ペアのみを C-business に手入力する。C-business は 2912 → 2913。A / B / C-wiring / D / E、分類と検査ルールは変更しない。生成レポート全体のコピーや予算の自動拡大は行わない。通常検査を再実行し、この追加以外に差分がないことを確認する。

## 現時点の制約

- Plan の個別関数名を強制する `tool_choice` は namespace と組み合わせた契約が未確認のため送信前に拒否する。`auto` / `none` / `required` は保持する。API キー・ローカル経路の既存の個別関数選択は変更しない。
- 実アカウントでの推論、実ローカル LLM、実 app-server は利用していない。
- Phase 6 全体の完了、全回帰・静的解析・クライアント検証、PR・マージ・マージ後 CI の成功はまだ主張しない。
