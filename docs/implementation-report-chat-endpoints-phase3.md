# Chat endpoints Phase 3 — CLI / Shell / terminal controls

## Scope

通常チャットの接続先を、Phase 1 のプロファイルと Phase 2 の Run 固定を使って選択する。OpenAI API、ChatGPT 利用枠、ローカル LLM ごとに選択状態を別実装しない。API・認証・利用経路は独立した設定のまま扱う。

- `/llm chat list` / `show` / `use [endpoint-name]`。既存の `/llm capture` を同じルートに保持する。
- Shell と terminal CLI の選択には既存の `MeetingSelectionMenu` を使用する。上下・Enter・Esc、現在選択・利用不可理由を文字でも示す。非対話環境では名前指定を案内し、既定候補を勝手に選ばない。
- `/model`、`/models`、既存のモデル API も同じ選択状態を使う。補助推論の `ModelHolderService` は変更しない。
- 選択はプロセス全体に適用し、次に受け付ける Run から使用する。受理済み Run の接続先・モデル・承認継続・Checkpoint は Phase 2 の固定値を維持する。再起動時は設定の active / model に戻る。
- 選択や補完は推論を送らず、履歴の外部送信を承認しない。通信成功やモデル互換性も意味しない。

## Shared boundary

`ChatConnectionService` がプロファイル、選択、受理済み Run、ネイティブ資源を所有する。`ChatModelSelection` は選択とモデル一覧の契約、`ChatEndpointSelection<P>` は公開用の接続先情報と選択の契約とする。HTTP アダプターはプロジェクションの内部を解釈しない。資格情報を含む設定オブジェクトは公開しない。

Shell のメニュー接続には ID と表示文字列だけを渡し、起動配線を接続先 DTO や設定型へ結合しない。API と Shell は同じ公開用プロジェクションを使用する。認証状態の `configured` は設定の存在、`unverified` は未検証を示し、認証・推論の成功を装わない。課金方式は設定された usage-path から表示し、ホスト名から推測しない。

名前付きプロファイルの表示は、既定モデル・既定キー・OAuth 更新へアクセスしない。モデル変更後はモデル固有の能力を未確認に戻す。非対応の明示設定は利用不可理由にし、非対応の継承設定は送らず省略理由を表示する。従来形式のプロファイルでは、実際に Run へ保存する生成設定と認証方式を表示する。設定解決に失敗しても秘密を含む例外を表示せず、利用不可として残す。

## HTTP and model catalogs

- `GET /api/v1/llm/chat`: 登録済み接続先の公開情報。
- `GET /api/v1/llm/chat/current`: 選択中の公開情報。
- `PUT /api/v1/llm/chat/current`, `{"endpoint":"local"}`: 登録名を選択。キー・URL・任意設定は受け付けない。
- 既存 `GET /api/v1/models`, `GET|PUT /api/v1/models/current` の応答形は維持する。

モデル一覧は取得開始時の接続先とモデルを固定し、一覧と同じ選択スナップショットを返す。取得中に他のクライアントが選択を変更しても、返った一覧を別接続先のモデルとして表示しない。推論専用の生成設定が未対応でも、モデル一覧の GET へそれらを持ち込まない。

SDK 4.101.0 の同じ明示的な資格情報・共有トランスポート基盤を使い、モデル一覧用クライアントは正確な `GET /models` と空の本文に限定する。推論、モデル取得・削除、宛先変更、認証なしへのキー注入を拒否する。キーは送信時に取得し、登録・宛先の束縛を送信直前にも検証する。None ではキー供給元を読まない。リダイレクト、別プロファイル、既定キー、有料経路への自動フォールバックはない。失敗時は外部の例外本文を公開しない。

Responses 本文のプロファイル別ポリシーは Phase 4、ChatGPT アカウントの認証・モデルカタログ・利用枠の統合は Phase 5 で扱う。この Phase は既存の本番 Responses ゲートを一括解除しない。未検証のサーバー・モデルを互換と認定しない。

## Architecture review

Run の受理境界と同様に、中立な契約を利用して、モデルコマンドや HTTP アダプターから具体的な接続管理実装への結合を避ける。メニューの起動配線にも接続設定型を渡さない。

補完は既存の `CompletionMetadata` / `CompletionContext` / `CompletionCandidate` を利用する。新しい補完フレームワークや Reflection による型隠蔽は作らない。既存の `LlmCaptureCommand.ActionCandidates -> FirstArgumentCandidates` と同じ llm → core.completion の方向であり、新しい機能間循環を追加するものではない。

最終 bytecode で追加辺を再確認し、必要な補完契約の辺だけを既存の個別レビュー手順に沿って記録する。起動配線・HTTP 具体実装への不要な辺は実装で解消し、許可集合やルールの一括拡大で通さない。

## Validation status

作業中。Red / Green の実行証拠と最終の全回帰・静的解析・CI の結果を、完了時に更新する。

- 接続先・補完・SDK 境界の関連 73 件: 成功。中立な公開情報ポートへの変更後も関連 60 件が成功。
- 接続先ごとのモデル一覧・従来設定表示を含む関連 85 件: 成功。
- terminal 最終 verify: 36 スイート / 205 件、failure / error / skip 0。配布 jar の構築も成功。
- 個別依存レビュー後の ArchitectureGuardTest / PackageArchitectureTest: 6 件成功。
- 全テストは既定の安全条件、所有する localhost / `.invalid` fixture を使用。実モデル・実アカウント・実サーバーの互換性試験とは区別する。
### Phase 3 の個別依存レビュー

起動配線から接続先 DTO / Profile への2辺と、HTTP アダプターから具体的な接続サービス / DTO への2辺は、中立な選択契約と ID・表示文字列のメニュー配線で解消した。C-wiring / D の予算は変更しない。

残る追加は、新しい `LlmCaptureCommand.EndpointCandidates` が既存の補完 SPI と不変データ型を使う次の3ペアだけである。

| 参照先 | 必要性 |
| --- | --- |
| `core.completion.CompletionMetadata` | 既存 Shell の補完 SPI を実装する |
| `core.completion.CompletionContext` | カーソル位置を含む既存 SPI の入力型 |
| `core.completion.CompletionCandidate` | 登録名と現在選択・利用経路・利用不可理由を説明付き候補として返す不変型 |

承認された作業範囲は依頼の Phase 3（既存の補完・ヘルプ・Shell 対応）であり、この個別変更も Phase 3 の PR でレビュー対象として明示する。新しい補完フレームワークへ迂回せず、既存 SPI の新しい実装として必要な型依存に限定する。Run・承認・権限・実行処理への依存を補完へ持ち込まない。

`docs/architecture/README.md` の個別レビュー手順に従い、上記3ペアだけを C-business と、その business 分類を E に手入力する。C は 2909 → 2912、E は 1074 → 1077。`ActionCandidates -> FirstArgumentCandidates` と同じ llm → core.completion の方向が旧予算に既に存在することを検証した。A / B / C-wiring / D は変更なし。生成レポートと旧予算の差分がこの集合と正確に一致し、解消済みの辺もないことを検証してから追加した。レポートの無条件コピー、ルール変更、抑制、テストの削除は行っていない。
