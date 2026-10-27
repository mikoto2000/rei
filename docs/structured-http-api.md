# 構造化 HTTP API

REST API は API 専用ツールがあればそれを優先し、汎用の `httpRequestRead` / `httpRequestWrite` を次に使います。
公開 HTML は `fetchUrlContent`、調査は `webSearchAndRead` を使います。Shell は既存の開発作業向けに残ります。

## READ

```json
{"request":{"url":"https://api.example.com/tasks","query":{"status":"open"},"jsonPointer":"/items","maxItems":5,"authProfile":"task-api"}}
```

GET が既定です。HEAD も指定できます。`query` と許可された `headers` は文字列の map です。
任意の Authorization/Cookie/Host/X-Api-Key は渡せません。Secret を URL や query に含めないでください。
`jsonPointer` は RFC 6901（`~0` は `~`、`~1` は `/`）。空文字列は文書全体です。
配列は既定 100 件、`maxItems` は 1〜1000。非配列はそのまま返します。

出力例（説明用であり実測値ではありません）:

```json
{"status":200,"contentType":"application/json","json":[{"id":1}],"text":null,"extracted":true,"truncated":true,"error":null,"timedOut":false,"cancelled":false,"outcomeUnknown":false,"responseBytes":250,"outputBytes":10}
```

`status` は HTTP の結果、`error` は処理エラーです。4xx/5xx の本文も上限内で返り、`HTTP_ERROR` が付きます。
204/HEAD は本文なしです。JSON と text は区別します。サイズ超過時に不完全な JSON を返しません。
`truncated` は配列の件数省略を示します。通信失敗時の本文切断を成功として扱いません。

## ホスト設定

Spring の管理者設定に追加します。LLM 用の設定登録ツールはありません。

```yaml
rei:
  http-api:
    max-bytes: 262144
    timeout-millis: 30000
    allowed-origins:
      - http://127.0.0.1:8081
      - https://api.internal.example:8443
    unsafe-read-prefixes:
      - https://api.example.com/actions
    auth-profiles:
      task-api:
        origin: https://api.example.com
        type: bearer
        secret-env: TASK_API_TOKEN
      internal-api:
        origin: https://api.internal.example:8443
        type: api-key
        header: X-Api-Key
        secret-env: INTERNAL_API_KEY
```

環境変数はホスト側の既存の Secret 注入手段で設定します。プロファイルは Secret を保存せず、参照名だけを保持します。
ChatGPT アカウント用の暗号化 store は用途が異なるため転用しません。
内部アクセスを許可する Origin は scheme・host・実効 port が完全一致するものだけです。
パス・query・fragment を含む allowlist は起動時に拒否します。Origin 登録はその接続先への内部 IP 通信を許可するため、管理者が信頼できる接続先だけを登録してください。
プロファイルに別 Origin を指定して Secret を転送することはできません。
`unsafe-read-prefixes` は既知の副作用 GET リソースを READ から排除します。その API は用途別の承認付きツールで扱ってください。

## セキュリティと互換性

既存 HTTP 基盤の DNS 回答検証、検証済み IP と実 peer の一致、TLS 証明書検証、圧縮前後サイズ上限、総期限、キャンセルを使用します。
環境プロキシを自動適用せず直接接続します。TLS 検証無効化の設定は設けません。
DNS 検証を接続ごとに行うため既存の `newConnection` を維持し、接続プールによる再検証省略は導入しません。
READ のリダイレクトは最大 5 回で同一 Origin に限定し、全 hop を再検証します。

一般的な Shell 実行権限には任意のプログラム経由でネットワークにアクセスできる制約があります。
HTTP ツールで拒否された処理を別ツールに移して回避してはいけません。Shell を自由に許可しながら全通信を JVM 内で遮断することはできません。
強い分離が必要なら OS/container のネットワーク制限も必要です。

既存 CLI/HTTP/MCP の ingress 制限、Agent Event、UI projection、Checkpoint の形式は変更しません。
MCP の既存 read allowlist を無断で拡張しません。SubAgent に公開するのは READ だけです。

## エラー対処

| error | 対処 |
| --- | --- |
| INVALID_INPUT | メソッド・ヘッダー・Pointer・件数指定を修正 |
| URL_NOT_ALLOWED | 管理者が接続先と副作用 API 設定を確認。Shell で迂回しない |
| HTTP_ERROR | status と本文から認証・権限・レート制限・サーバー障害を確認 |
| INVALID_JSON | API の Content-Type と JSON 本文を確認 |
| POINTER_NOT_FOUND | 実際の JSON 構造に合う Pointer を指定 |
| EMPTY_BODY | 本文なし API では Pointer を省略 |
| WIRE_LIMIT / DECODED_LIMIT | API 側のページング・fields を使用。無制限に上限を増やさない |
| CONNECT_TIMEOUT / READ_TIMEOUT / TOTAL_TIMEOUT | 接続先と期限を確認。WRITE は結果不明なら状態照会を先に実施 |
| CANCELLED | 実行を停止。WRITE を無条件に再実行しない |
| NETWORK_ERROR | DNS/TLS/接続障害をホスト側で調査。証明書検証を無効化しない |

## 移行

`Invoke-RestMethod -Uri 'https://api.example.com/tasks?status=open'` の代わりに READ 入力例の構造化 URL/query を渡します。
PowerShell の引用符や JSON エスケープを組み立てる必要がなく、認証値も引数に含めません。
JSON Pointer と件数制限でモデルに返す情報を抑えられます。

調査・設計の根拠は [Phase 0](structured-http-api-design.md) を参照してください。
