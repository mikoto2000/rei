# 自発的行動の実装状況

Phase 2 では、公開利用を明示的に許可した話題を使い、Shell から投稿案を手動生成できます。
生成は未承認の `PENDING_REVIEW` ドラフトの保存で終了します。
GUI・Shell の確認・編集画面、通知、定期実行、承認付き公開は後続 Phase です。
既定では無効です。有効化しても定期実行や外部公開は開始しません。

## 設定

```yaml
rei:
  autonomous-action:
    enabled: false
    bluesky:
      enabled: false
      cron: "0 0 12 * * *"
      zone: "" # 空なら既存 Clock の zone を継承
      max-drafts-per-day: 1
      max-pending-drafts: 1
      cooldown: 1h
      draft-expiration: 72h
      max-regenerations: 3
      regeneration-cooldown: 1h
      system-prompt: |
        あなたは AI 秘書のれいです。
        公開許可された話題から投稿案を考えてください。
        話題がなければ SKIP を返してください。
```

設定は起動時に読み込みます。不正な cron・zone、正でない生成/保留上限・期間、
負の再生成上限、空または過大な prompt、未知の設定項目を拒否します。
検討頻度と生成上限は独立しています。設定によって公開権限は付与されません。

## 手動生成と公開用話題

既存の `rei.bluesky.handle` を投稿案の宛先として使います。公開 handle から DID を解決し、
アプリパスワードの読み取りや Bluesky の認証・投稿は行いません。
`rei.autonomous-action.enabled` と `rei.autonomous-action.bluesky.enabled` を両方有効にしたうえで、
既存の対話 Shell で操作します。軽量 CLI・GUI への操作追加は後続 Phase です。

```text
/autonomous add-source --text "公開用の話題"
/autonomous sources
/autonomous source <sourceId>
/autonomous permit-source <sourceId> --revision 1
/autonomous generate
/autonomous edit-source <sourceId> --revision 2 --text "編集した話題"
/autonomous permit-source <sourceId> --revision 3
/autonomous revoke-source <sourceId> --revision 4
```

登録時の既定は非公開です。最初から公開利用を許す場合だけ `add-source --public-use` を指定します。
編集・許可・取消は確認したリビジョンを必須とし、古い値を拒否します。本文編集は必ず許可を外し、
改めて明示的に許可するまで生成に使いません。旧本文、許可状態、操作元は履歴として保持します。
`sources --limit 20` は最新の管理用一覧です（1～200 件）。管理用データを LLM に渡しません。

生成時は SQL で許可済みの現行本文・ID・リビジョンだけを選びます。未許可本文と操作元は
取得しません。最大 32 件、各 8 KiB、合計 32 KiB の UTF-8 本文を利用し、超過分は使いません。
通常の会話、Memory、Activity、ローカルファイル、非公開の見送り理由は生成コンテキストに入りません。
公開可能な話題がない場合やモデルの `SKIP` は正常終了です。失敗時に再生成ループは行いません。
機会 ID、保存済みの上限・クールダウン・排他は手動実行でも適用されます。

## LLM 接続と応答の検証

既存の LLM 提供機構を使い、通常会話用の ChatClient/advisor を経由せず、一回のモデル呼び出しを行います。
必要なら `rei.llm.features.autonomous-bluesky` で既存の機能別接続設定を指定できます。
明示的に設定されたフォールバックは一回だけ使い、SDK 内部の再試行は無効です。
通常会話の接続選択、履歴、ツール、実行イベント、取得記録を引き継ぎません。
利用する既定モデルに `user`、metadata、cache key、追加リクエスト本文、stop、schema、
tool context 等の周辺情報がある場合は送信前に拒否します。
フォールバックなしの専用接続では、未使用の既定接続の周辺情報は引き継ぎません。
この Phase では Chat Completions を使い、未対応の Responses 設定は送信前に拒否します。
出力上限は設定値と 1024 tokens の小さい方、各接続のタイムアウトは設定値と 60 秒の小さい方です。
この期限は応答の読み取り完了まで適用し、少しずつデータが届き続けても延長しません。
自発生成の同期呼び出し中だけ既存 HTTP interceptor が SDK の期限を保持し、
終了時にはその設定を解除します。既存の接続・認証設定と通常会話のタイムアウト動作は維持します。
フォールバックを使う場合は、接続ごとにこの上限が適用されます。

モデルには専用の system prompt と、公開本文・リビジョン ID の JSON だけを渡します。
資料内の指示はデータとして扱い、ツール呼び出しを返しても実行しません。
応答は `SKIP` または `PROPOSE_POST` の厳密な JSON とし、未知の項目、重複キー、
余分な JSON、実在しない情報源 ID、承認・返信・メディア等の項目を拒否します。
本文は 300 graphemes / 3000 UTF-8 bytes 以下で、通常の URL・メールは本文のまま保持し、
メンション、無効な Unicode、空白・書式制御文字だけの本文、危険な制御文字を拒否します。
空白や改行を含む元の本文をそのままハッシュ化し、無断で正規化しません。
モデルの理由は非公開の確認資料として保存します。公開済み履歴・公開許可済みフィードバックの
利用と類似投稿抑止は Phase 7 で接続します。

## 基盤の責務

- `AutonomousActionPolicy` は現在の事実だけを評価し、生成を検討してよいかを返します。
  LLM・投稿サービス・承認処理を持ちません。
- `AutonomousDraftStore` の保存操作を `SqliteAutonomousDraftStore` が実装し、
  既存 `StorageDatabase` の transaction を利用します。機会 ID、
  実行中 claim、日付境界、pending 上限、試行 cooldown を永続状態で検証します。
  手動・定期の契機は同じ制約を通ります。
- 本文・SHA-256・reason・source IDs は immutable な revision として保存します。
  編集は新 revision を追加し、過去 revision を書き換えません。承認・送信情報は初期値 null です。
- 見送り理由は既定で非公開です。保存しただけでは LLM 用の情報源になりません。
- 期限切れと見送りは編集できません。古い revision による操作を拒否します。
- 起動時に残った実行中の検討は ABORTED とし、監査を残します。自動で再生成しません。
  既存 `application.shutdown.started` で新規受付を停止し、停止後の生成結果は保存しません。

行動判断・生成サービスは保存操作と公開情報源・宛先解決のインターフェースだけに依存します。
SQLite 実装は `storage`、モデル接続は `llm`、公開 handle 解決は `bluesky`、
停止イベントの接続は `event` に置き、既存パッケージから自発的行動への循環依存を作りません。

## 保存と移行

storage.db の schema 23 で、`autonomous_opportunities`、`autonomous_drafts`、
`autonomous_draft_revisions`、`autonomous_audit` と必要な indexes を追加します。
既存の起動前 migration・検証済み backup・data directory lease を再利用します。
機会 ID の一意制約、同時実行中の機会を一件に制限する unique index、状態・revision の
制約を持ちます。移行だけでは機能を有効化しません。
schema 24 は `autonomous_public_sources` に本文・公開利用許可・操作元のリビジョンを追加します。
現行版は ID ごとに一件だけとし、旧リビジョンを上書きしません。

本文と revision は短い同一 transaction に保存します。Project/Run 所有の ArtifactStore に
仮の Run を作成せず、今回の本文保存には使用していません。
一般の監査行には ID、操作、reason code、日時を保存し、本文やシステム prompt は複製しません。

## 安全性と後続の接続

自発処理は最終的にもドラフト保存と確認通知までとし、外部公開は人間の明示操作で
別に実行します。通常の Tool 自動承認を投稿承認として使いません。
承認 UI は既存機構の再利用を優先し、新しい本人確認方式や OS サービスの追加を必須にしません。
同じ OS 権限と API キーを持つ任意プログラムを識別できない既存の信頼境界は、
ドラフト保存によって解消したとは扱いません。公開段階の保証と残存する前提は
[Phase 0 設計](autonomous-action-design.md) を参照してください。

内部状態の具体的なパラメータは追加していません。将来は policy の context と
生成用の公開可能な表現情報に接続し、保存・上限・承認・送信の安全条件から分離します。

## 検証

時刻を制御した policy・settings・Unicode 本文制限・永続化・再起動・編集・見送り・
期限・同時起動・停止イベント・移行のテストを追加しています。
新しいテストは通常の JUnit として Maven/CI から実行します。
開発中は同じ JUnit を対象クラスだけコンパイルする補助 runner でも実行し、
拒否条件の Red → Green を短い周期で確認しています。補助 runner は製品に含めません。
外部サービスや実アカウントへの投稿は検証に使用しません。
自発生成の時間制限は、ローカル HTTP サーバーが有効な応答を少しずつ返すテストで、
既定接続・専用接続・フォールバックを検証します。時間切れ後の通常呼び出しでも
従来の読み取りタイムアウト動作と接続設定が維持されることを確認します。
