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

## WRITE と承認

```json
{"request":{"url":"https://api.example.com/tasks","method":"POST","authProfile":"task-api","headers":{"Idempotency-Key":"task-create-123"},"body":{"title":"example"}}}
```

POST/PUT/PATCH/DELETE は `NETWORK_WRITE` と `EXTERNAL_SIDE_EFFECT` が必要です。
汎用 POST が読み取り用途でも WRITE として扱います。READ の許可や SubAgent の承認継承では実行できません。
既存の Policy/Permission、`/approval show`・承認・再開のフローを使います。承認対象の入力に URL・method・resource・body が含まれます。
`httpRequestWrite` を READ に再分類する設定は適用しません。古い権限制御無効モードでも新規 WRITE は承認を要求し、実行 run のない旧 SDK 経路は拒否します。
管理者が明示的に WRITE を自動承認する場合や full-auto は既存設定に従います。
Secret は承認入力に含めません。プロファイル名だけを確認できます。

body の省略は空本文、JSON `null` は 4 バイトの `null`、object/array/scalar は JSON 本文です。
本文を送る場合 Content-Type は `application/json`。multipart/binary は対象外です。
リダイレクトは `WRITE_REDIRECT_REFUSED`、429/503 も自動再試行しません。
タイムアウトやキャンセル後の `outcomeUnknown` は「未実行」を意味しません。先に用途別の状態照会で結果を確認してください。
`Idempotency-Key` は API が対応する場合のみ有効であり、このツールがサーバー側の重複排除を保証するものではありません。

## 認証・内部アクセスの設定

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

環境変数はホスト側の既存の Secret 注入手段で設定します。プロファイルは Secret を保存せず、参照名だけを保持します。応答内の既知の認証フィールド（access_token など）は既存 CredentialRedactor で伏せ、適用した Secret の反射も解析後に再確認します。
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
| AUTH_PROFILE_NOT_FOUND | ホスト設定のプロファイル名を確認 |
| AUTH_ORIGIN_MISMATCH | プロファイルとリクエストの Origin を確認。Secret は送られていない |
| AUTH_SECRET_UNAVAILABLE | ホスト側の環境変数を確認。Secret を会話に貼らない |
| WRITE_REDIRECT_REFUSED | 管理者が正しい API URL を確認。自動再送しない |

## 移行

`Invoke-RestMethod -Uri 'https://api.example.com/tasks?status=open'` の代わりに READ 入力例の構造化 URL/query を渡します。
PowerShell の引用符や JSON エスケープを組み立てる必要がなく、認証値も引数に含めません。
JSON Pointer と件数制限でモデルに返す情報を抑えられます。

調査・設計の根拠は [Phase 0](structured-http-api-design.md) を参照してください。

## 計測とローカル比較（2026-10-28）

Micrometer の既存 global registry に `rei.http.api.*` を集計します。URL・headers・body はラベルやログに保存しません。
`calls` は read/write 別呼び出し、`requests`/`redirects` は実送信試行、`http{status}` は HTTP 状態、
`errors{code}` は通信/パース/抽出等の固定エラー分類、`truncations` は件数省略です。
`request.duration` は HTTP exchange、`tool.duration` はサービス全体（引数処理・抽出込み、承認待ち除外）。
`wire.bytes` は受信 byte counter、`response.bytes`/`output.bytes` は本文 byte の分布です。
`estimated.tokens` は本文の conservative estimator による概算で、実際の LLM tokenizer の値ではありません。
`retries` はこの実装では 0。`shell.calls` は実際の runCommand のうち判別できた HTTP コマンドだけを数えます。
承認回数・待ち時間は既存 ToolApprovalRepository と Timing の APPROVAL_WAIT、実行境界全体は既存 tool event で観測します。
専用の高頻度ファイルログや SQLite 保存は追加しません。registry に export 先がなければ永続化されません。

Windows 11 / JDK 25、ローカル 23,991 bytes の JSON（100 件）、LLM なしで測定しました。
A は実際の Tools.runCommand foreground → powershell -NoProfile → Invoke-RestMethod → ConvertTo-Json、
B は HttpApiService.read です。両方で全件と 5 件抽出を測り、成功時はモデルへ返せる result 全体の JSON byte 数を測っています。
この再測定中は同じホストで全体回帰と静的解析も動作しており、負荷を隔離したベンチマークではありません。
承認・LLM 推論・本番ネットワークは含みません。B は 1 回目を別記し、その後の 5 回を中央値で示します。

| 条件 | Shell 中央値 ms | HTTP 中央値 ms | Shell 成功 | HTTP 成功 | Shell result bytes | HTTP result bytes |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| full | 1316.131 | 11.112 | 5/5 | 5/5 | 25354 | 24207 |
| extracted | 1269.846 | 7.783 | 5/5 | 5/5 | 1575 | 1404 |

B の初回実行は 576.516 ms。B は抽出により 24,207 → 1,404 bytes
（94.20% 削減）でした。Shell でも抽出すれば本文量は削減できます。
引数全体の JSON byte は全件 A=203, B=157、5 件抽出 A=234, B=159。
400 fixture では A は exit=1 と stderr、B は status=400・HTTP_ERROR・JSON エラー本文を分離して返しました。
これは小規模なローカル計測であり、一般的な速度倍率や本番成功率を保証しません。
LLM を含む E2E、実トークン数、実サービスのレート制限下の比較は未測定です。

[実測サンプル](measurements/structured-http-api-20261028.json) を参照してください。再実行:

```powershell
./mvnw.cmd -B -Pfull "-Dtest=StructuredHttpBenchmarkTest" "-Dhttpapi.benchmark=true" test
```

`target/http-api-benchmark.json` を生成します。fixture サーバー以外へ通信しません。
用途別ツールの追加は、今後の calls/error/サイズの傾向を調べてから提案します。特定サービスの専用ツールは今回追加しません。

## 最終修正と検証

最終修正の対象テストは 62 件成功（failure/error/skip 0）。HTTP、Policy、旧 SDK 境界、Secret 反射、
immutable な request metadata、TLS 拒否、ArchitectureGuard を含みます。
最初の SpotBugs ゲートは 11 件の新規指摘で失敗し、Map の defensive copy、charset の単一取得、
既定値の boxing を修正しました。Spring/Micrometer の共有注入、および分類済み HTTP 例外の伝播は
個別の SuppressFBWarnings と理由を記録しました。静的解析の既存警告予算は増やしていません。
再解析のゲートは Checkstyle 0 件、SpotBugs 1,888 件すべて既存予算内、新規 0 件で成功しました。
基準 main の StorageDatabase.activityTransaction にも既存予算未登録の runtime 例外伝播の指摘があり、
キャンセル時の元の例外を保つ既存動作を維持する注釈だけを加えています。
追加 fixture の PKCS12 と fixture-password はローカルの未信頼証明書テスト用であり、本番認証に使用しません。
