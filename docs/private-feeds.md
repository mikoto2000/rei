# 認証付き RSS / Atom

公開フィードの使い方は従来どおりです。非公開フィードには、**読み取り専用・クライアント別**の Bearer token を環境変数から付けられます。投稿用 OAuth / API credential は使用しません。

## 設定

Rei の `application.yaml`（保存先は `/config path` で確認）に、購読 URL、許可する origin、環境変数の**名前だけ**を記載します。

```yaml
rei:
  feed:
    authentication:
      - url: https://your-feed.example/feed.xml?limit=30
        allowed-origin: https://your-feed.example
        token-env: REI_TENTEN_FEED_TOKEN
```

- `token-env` は文字どおりの環境変数名です。`${REI_TENTEN_FEED_TOKEN}` や token 本体を記載しないでください。
- 発行元で 32 random bytes 以上から作成されたクライアント専用 token を使います。この機能は base64url 文字 `A–Z a–z 0–9 _ -` の 43〜128 文字を受け付けます。文字列の長さだけで乱数の品質は検証できないため、安全な乱数での発行が必要です。
- token は OS / 起動環境の秘密情報管理から Rei プロセスの環境変数へ渡します。シェル履歴、チャット、API リクエスト、設定ファイル、URL に値を入力しないでください。
- origin は HTTPS の scheme / hostname / port を照合します。省略された HTTPS port は 443 と同じです。path、query、fragment、userinfo を含められません。
- URL は path / query を含めた完全一致で対応付けます。`?limit=30` の有無や query の変更も別の URL です。1 URL に複数の設定は許可しません。最大 100 件です。
- 設定変更は Rei を再起動して反映します。環境変数は取得時に解決しますが、外部から実行中プロセスの環境を書き換えることはできないため、token 更新時も通常は再起動します。
- 設定の欠落・誤記に注意してください。認証設定に一致しない URL は従来どおり公開フィードとして扱われます。設定を削除しても DB 内の購読は残ります。非公開の発行元は必ず未認証アクセスを拒否してください。

## CLI / API から購読する

CLI は URL だけを登録します。秘密を引数にする新しいオプションはありません。

```text
/feed add --name "てんてんニュース" "https://your-feed.example/feed.xml?limit=30"
/feed list
/feed update <ID>
```

既存 Web API の `POST /api/v1/feed` も同じです。リクエスト例:

```json
{"url":"https://your-feed.example/feed.xml?limit=30","displayName":"てんてんニュース"}
```

これは Rei Web API 自体の認証とは別の設定です。リクエスト・応答やフィード DB に reader token を追加することはありません。OPML は秘密を含めない URL だけを取り込み、認証設定は別途上記で行います。URL 内の userinfo、fragment、既知の認証 query（`token` / `access_token` / `api_key` など）は拒否されます。

## 安全な取得とトラブルシューティング

- 対象フィードへ `Authorization: Bearer …` だけを付けます。任意ヘッダー、Basic 認証、URL token は対応しません。
- 必要な環境変数が未設定・空・形式不正の場合、HTTP リクエストを送らず失敗します。公開フィードへのフォールバックはしません。
- 認証付きリクエストでは同一 origin を含めて**すべての redirect を拒否**します。発行元の最終 URL を確認し、URL と origin の設定を更新してください。
- HTTP 401 は token / 環境変数と発行元での失効状態を、403 は発行元の読み取り権限を確認してください。エラーは status と確認事項だけを示し、レスポンス本文・Location・token を含めません。
- 記事ページの取得や別フィードには reader token を引き継ぎません。RSS 内に必要な本文や要約を含めてください。
- アプリは token を DB・OPML・ログへ保存しません。JDK HTTP ヘッダー / wire debug logging、HTTP proxy のヘッダー記録、環境変数 dump を有効にしないでください。秘密情報を扱える OS 管理者・デバッガーからの保護は、この機能の対象外です。
- 発行元は token を RSS 本文へ出力しないでください。成功レスポンス内の token 本体の反射も除去しますが、任意の符号化・変換された秘密まで検出する仕組みではありません。旧バージョンで DB / OPML / 履歴へ保存した秘密の消去は行いません。既存の認証情報入り URL は取得を拒否し、購読一覧の URL は伏せます。既に URL に token を入れていた場合は発行元で失効・再発行してください。

認証付き HTTP（非 TLS）は本番では許可されません。ローカル HTTP はテスト fixture 専用の非公開コンストラクタでのみ利用できます。
