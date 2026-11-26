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

## 個別依存レビュー（推論送信の所有者確認）

追加対象は `dev.mikoto2000.rei.core.policy.InferenceExportGuard -> dev.mikoto2000.rei.core.chat.AgentRunContext` の 1 ペアだけである。

承認判定は既存の `ToolApprovalRepository.request/consume` と、その API が受け取る実際の Run 所有者を再利用する。Project・root・Session・Run・mode・source・voice を承認に結び付け、単に同じ接続先を選んだ別の Run が承認を使えないようにする。既存の承認保存やツール権限ガードも `AgentRunContext` を受け取る。別の所有者台帳や推論用の独立した承認ストアを作らず、この型依存を必要な境界として扱う。依頼された Phase 6 の送信権限分離の範囲で、PR の個別レビュー対象に含める。

最初の検査で報告された Mode・RequestSource・ResponseStyle の 3 型への依存は実装で解消した。mode/source は既存の中立な文字列取得メソッドを使用する。ResponseStyle は既存定義上、表示だけに関わり実行権限を変更しないため、権限の識別条件に使用しない。実際に送信するシステム指示や本文が変わる場合は内容ハッシュが変わる。

最新の通常コンパイルと full プロファイルの検査で、追加が所有者型への 1 辺だけ、解消済み辺は 0、他の予算の変更は 0 と確認した。この 1 ペアのみを手入力し C-business を 2913 → 2914 とする。Phase 6 のここまでの追加は、内容固定の配信属性保持と、この所有者確認の計 2 ペアである。列挙型への不要な依存、分類・検査ルール・抑制の追加は受け入れない。


## 共通入力と承認内容の整合性（統合は継続中）

名前付き Chat Completions と Responses の両方で、同一の Run 所有者と受理済み接続の fingerprint に保護入力を結び付ける。フラットな旧 Chat Completions と静的 helper は既存経路を維持する。接続・モデル・イベントの関連 78 テストとアーキテクチャ検証を通過した。

同梱 SDK の Chat Completions 応答は、終了理由の列挙型や注釈などをメッセージ metadata に含むため、そのままでは厳密な保存形式に書けなかった。この失敗を実際の `PinnedChatModels` と所有する loopback HTTP fixture で再現し、名前付き経路の SDK 境界で型を確認してポータブルな JSON snapshot に変換する。本文・parts・既知の metadata は保持し、任意の SDK オブジェクトの直列化は認めない。共通 codec が受け取る追加項目は文字列 `rei.llm.chat-native` のみである。blocking / streaming と通常チャット 3 モードの保存を含む関連 93 テストが成功した。この検証は実モデルの互換性・注釈や追加プロパティの全種類を確認したものではない。

推論待ちには既存の保護メッセージ参照を使い、架空の assistant tool call や tool result を作らない。`InferenceExportRequest` は内容 digest に各メッセージ、公開ツールの名前・説明・JSON schema・returnDirect・確認属性を含める。保存前は Run が公開した参照から、復元時は承認待ちが保護した参照から本文を再構築し、内容 digest を再計算する。本文・定義・digest の差し替え、保存後の digest 不一致を拒否する関連 44 テストが成功した。

承認の結び付き確認には既存 `tool_approvals` の scoped digest を使う。承認 ID・project・Session・Run・目的 `inferenceExport`・受理済み接続・内容が一致することを、権限の付与や消費を行わずに照合する。別ツールの承認 ID、別 Run、別接続、別内容を拒否する関連 45 テストが成功した。これらの変更ではアーキテクチャのペア追加はない。

ここまでの内容保護・照合の成功は、通常実行への送信同意統合完了を意味しない。実際の本文の確認、明示的な承認待ち、通常実行の呼び出し直前の制御、再開時の予算と所有者の継続、動的ツール選択との統合を完了するまで、Plan の通常経路は閉じたままである。


通常チャットへの送信前待機の接続では、`RunContinuation` の既存所有者・保存・キューを使う。推論同意のない名前付き入力は `StagnationChatModel` の実際の呼び出しより前で待機へ移し、呼び出し済みフラグ・利用量不明・要求枠・資格情報取得に進めない。両 API × 3 モードでモデル fixture が一度も呼ばれないこと、予算 snapshot を変えないこと、本文参照と受理済み接続を承認待ちに保存することを確認した。関連 74 テスト（従来のループ 13、アーキテクチャ 6 を含む）が成功した。

待機を通知するための `ApprovalContinuationService -> RunWaitingException` 依存は追加せず、保存済み待機を Optional の空値で返し、既存の実行制御側が停止シグナルへ変換する。アーキテクチャの全 6 ペア集合は既存予算と一致し、ここまでの明示的な追加は前述の 2 ペアだけである。承認後の実送信・再開時の予約予算・ContextAssembler 有効経路と各種 fixture の承認設定は別途統合検証を完了する必要がある。


## 承認する本文の確認と続行受付

既存 `/approval show` と project-scoped `GET /api/v1/projects/{projectId}/approvals/{id}/inference` で、保護済みの system/user/history・parts・公開ツール定義と送信先を確認できる。表示は権限を付与・消費せず、一覧には本文を展開しない。内容と承認の目的・所有者・接続の照合が済んだデータだけを返す。保存用 JSON mapper のノード型を Web API に渡さず、通常の JSON データへ投影する。表示用の接続情報には中立な `AcceptedRunOptions` を使う。この範囲の関連 59 テストと全 6 アーキテクチャ集合の一致を確認した。

承認受付・復元・キュー投入時にも目的と内容を確認する。承認更新の短い SQL transaction に、独自の `BEGIN IMMEDIATE` を使う既存ペイロード読み取りを重ねない。本文は先に保護参照から検証し、権限を付与する transaction 内で文書が検証済みのものと完全一致することを再確認する。検証後にツール説明を変更する競合を注入し、承認が付与されず元の PENDING が維持されることを確認した。既存の保存ストア全体の transaction 規則は変更していない。目的・内容の変更を各境界で拒否するテスト、従来の再起動テストを含む関連 69 テストが成功し、アーキテクチャの追加・削除はなかった。
