# 自発的行動の実装状況

Phase 3 では、公開利用を明示的に許可した話題を使い、GUI・従来 Shell・軽量 CLI から
投稿案を手動生成し、一覧・全文確認・編集・再生成・見送り・保留ができます。
生成は未承認の `PENDING_REVIEW` ドラフトの保存で終了します。
通知、定期実行、承認付き公開は後続 Phase です。投稿承認・公開の操作経路はまだありません。
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
従来 Shell または接続済みの軽量 CLI で操作します。

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

## 投稿案の確認と編集

GUI の「投稿案」を開くと、接続中のサーバー全体の投稿案を確認できます。
Project や Session の選択は不要です。「全文を確認」から本文、投稿先 handle/DID、
理由、参照資料 ID、改訂番号、作成日時、有効期限、試行回数、結果を確認します。
本文と理由は文字列として表示し、モデルが HTML や操作指示を書いても実行しません。

「本文を編集」→「編集を保存」、または「再生成」の応答には、保存した最新の改訂番号と
全文を表示します。新しい本文を確認するまで前の版の確認を引き継いだとは扱いません。
「見送る」の理由は任意・非公開で、再生成用の入力に渡しません。
「保留して閉じる」は画面を閉じるだけで、`PENDING_REVIEW` や期限を変更しません。
期限切れ・見送り済みの投稿案は編集・再生成できません。

```text
/autonomous drafts --limit 20
/autonomous draft <draftId>
/autonomous edit <draftId> --revision 1 --text "編集した本文"
/autonomous regenerate <draftId> --revision 2
/autonomous hold <draftId>
/autonomous reject <draftId> --revision 3 --reason "今回は見送る"
```

Shell と軽量 CLI はサーバーの結果を JSON で表示し、編集・再生成後も全文を出力します。
`hold` は読み取りだけです。更新操作には確認した `--revision` が必須で、
他の画面で編集・見送りされた場合は conflict として拒否します。
GUI でも競合・通信切断時は更新して現在の状態を確認し、自動で変更操作を再送しません。
生成・再生成のクライアント通信期限は本文の受信完了まで 180 秒、その他は 30 秒です。
時間切れはサーバー側の取り消しを保証しないため、再操作の前に一覧・詳細を確認してください。

再生成は、既定で投稿案一件につき最大 3 回・試行間隔 1 時間です。
新規生成の一日上限や保留件数上限とは別に判定します。モデル呼び出し前に試行を永続化し、
`SKIP`・失敗・再起動でも消費した回数と時刻を戻しません。
`SKIP` と失敗では元の本文を保持します。生成中に編集・見送り・期限切れとなった場合も
その操作を上書きせず、再起動後に生成を自動再開しません。
再生成には利用許可済みの現行話題だけを使い、編集した投稿本文や非公開の見送り理由を送りません。

GUI の「公開用の話題を管理」から、話題の登録・本文編集・利用許可・取消を操作できます。
新規登録の利用許可は既定でオフです。編集中は保存済みの話題への許可変更を無効にし、
保存後の新しい改訂番号に対して明示的に許可します。本文編集では必ず許可が外れます。
サーバー切り替え時は前のサーバーの本文・入力を消し、遅れて届いた応答も表示しません。

## HTTP API

GUI・軽量 CLI は既存の API キー認証と固定ルートを使います。
従来 Shell と同じ保存・生成サービスを呼び、HTTP を経由しただけで承認済みにはしません。

| 操作 | ルート |
| --- | --- |
| 一覧・詳細 | `GET /api/v1/autonomous/drafts[?limit=20]`、`GET /api/v1/autonomous/drafts/{id}` |
| 手動生成 | `POST /api/v1/autonomous/drafts/generate`（空の JSON） |
| 編集 | `POST /api/v1/autonomous/drafts/{id}/edit`（`expectedRevision`、`text`） |
| 再生成 | `POST /api/v1/autonomous/drafts/{id}/regenerate`（`expectedRevision`） |
| 見送り | `POST /api/v1/autonomous/drafts/{id}/reject`（`expectedRevision`、任意の `reason`） |
| 話題一覧・詳細 | `GET /api/v1/autonomous/sources[?limit=20]`、`GET /api/v1/autonomous/sources/{id}` |
| 話題登録 | `POST /api/v1/autonomous/sources`（`text`、任意の `publicUseAllowed`、既定 false） |
| 話題編集 | `POST /api/v1/autonomous/sources/{id}/edit`（`expectedRevision`、`text`） |
| 話題の許可・取消 | `POST /api/v1/autonomous/sources/{id}/permit`、`/revoke`（`expectedRevision`） |

一覧は 1～200 件、不正入力は 400、存在しない ID は 404、古い改訂番号などの競合は 409 です。
更新リクエストの未知の項目を拒否し、承認フラグや任意の生成コンテキストを受け付けません。
生成結果の `SKIPPED` は正常な見送り、`FAILED` は失敗です。再生成では現在の本文も返します。
生成の有効フラグがオフでも、保存済み投稿案の確認・編集と話題の管理は利用できます。

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
schema 25 は検討機会に `expected_revision` を追加し、再生成対象の ID と確認した版を
永続 claim に結び付けます。完了時もその版・状態・期限を検証し、競合した結果は保存しません。

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
Phase 3 では、実際の認証付き HTTP、全クライアントの固定ルートと改訂番号、再生成中の
競合・再起動・上限、GUI の二重送信・全文表示・サーバー切り替え、デスクトップ/モバイルの
確認操作も模擬サーバーと fixture で検証します。ローカルで既定のテスト用ポート 1420 が
使われている場合は、`REI_CLIENT_E2E_PORT` に別のポート番号を指定して E2E を実行できます。
