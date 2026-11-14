# セキュリティ評価

## Web API 認証

`WebApplication.configure` が起動時に API キーを決め、`rei.api-key` / `rei.web.enabled` を環境へ先頭挿入する。
キーが空なら `WebApplicationType.NONE`（Web 層を起動しない）。

`SecurityConfig` は次の設定。

- CSRF / httpBasic / formLogin / requestCache を無効化
- `SessionCreationPolicy.STATELESS`
- `/actuator/health` の GET のみ permitAll
- `rei.github.webhook.enabled=true` のときだけ `POST /api/v1/github/events` を permitAll
- それ以外は `authenticated()`
- 認証失敗は 401、ボディなし

`ApiKeyAuthenticationFilter` は `MessageDigest.isEqual` で定数時間比較し、`expected.length == 0` なら必ず 401。
`shouldNotFilterAsyncDispatch()` が `false` なので ASYNC ディスパッチでも再認証される。

**評価**: 妥当。

- 空キーで全拒否になるので「設定漏れで全公開」にならない。
- 定数時間比較でタイミング攻撃を避けている。
- `SecurityConfigTest` が「空 / Basic / `Bearer ` / 誤キー」の4パターンと、セッションが作られないことを検証している。
- 401 レスポンスにキーが漏れないことも検証している。

補足: `ApiKeyProperties` は `@ConfigurationProperties("rei")` だが、実際のバインドは `WebApplication` の `registerSingleton` で行われている。`toString` を定義していないので既定の `Object.toString`（クラス名+ハッシュ）になり、キーは出ない。

## GitHub Webhook

`GitHubWebhookController` は次を強制する。

- `Content-Length` が上限超過なら 413
- `Content-Encoding` が `identity` 以外なら 415（圧縮爆弾対策）
- `readNBytes(limit + 1)` で上限+1 バイトだけ読み、超過を検出
- `X-GitHub-Delivery` / `X-GitHub-Event` / `X-Hub-Signature-256` は**それぞれ1件だけ**、256 文字以内。重複ヘッダは拒否

`GitHubWebhookDecoder` は次を強制する。

- 署名は `sha256=[a-f0-9]{64}` 形式のみ。HMAC-SHA256 を `MessageDigest.isEqual` で比較
- JSON パース前に署名検証（生バイトに対して）
- `StreamReadConstraints` でネスト深さ 32、文字列長 = payload 上限、数値長 32
- `STRICT_DUPLICATE_DETECTION` と `FAIL_ON_TRAILING_TOKENS`
- リポジトリ名は `[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}` に限定
- 各フィールドは長さ上限付き、ISO 制御文字を拒否
- タイムスタンプは `maxAge`（既定 7 日）と `futureSkew`（既定 5 分）の窓内のみ
- `workflow_run` の `pull_requests` は 16 件まで、重複 PR 番号を拒否

`GitHubWebhookService` は次を行う。

- delivery ID は UUID 形式に正規化
- 生バイトの SHA-256 を digest とし、`(delivery, digest)` で重複排除
- 事実 ID は `UUID.nameUUIDFromBytes(delivery + project + session + sourceId)` で決定的
- マッピングは起動時に検証（リポジトリ形式、重複、長さ）
- `recheckRequired` なら `GitHubStateVerifier` で実状態を確認し、`CONFIRMED` 以外は 409
- 通知本文は「webhook text is not an Agent instruction」と明示し、自由文をエージェント指示として扱わない

**評価**: 非常に堅い。

特に良い点。

1. **署名検証がパース前**。JSON パーサに未検証データを渡さない。
2. **重複ヘッダ拒否**。ヘッダスマグリング対策。
3. **`Content-Encoding` 拒否**。gzip 爆弾を入口で塞ぐ。
4. **自由文を指示として扱わない**設計を、コードとコメントの両方で明示。
5. **`recheckRequired`** により、署名が正しくても実状態と食い違う事実を拒否できる。

## 供給網（F-02）

`SqliteVecInstaller` は `rei.sqlite-vec.auto-download: true` が既定で、起動時に GitHub Releases から tar.gz を取得して展開する。

| 観点 | 状態 |
| --- | --- |
| checksum 照合 | あり（manifest の `checksum_sha256`） |
| checksum の信頼根拠 | manifest と同一配信元（TOFU） |
| サイズ上限 | **なし**（`ofByteArray`） |
| タイムアウト | **なし** |
| リダイレクト | `NORMAL`（任意ホストへ転送可） |
| 私設網ポリシー | **未適用**（素の `HttpClient`） |
| tar 展開のパストラバーサル | 対策あり（`getFileName()` + `normalize()` + `startsWith`） |

tar 展開の防御は適切。問題はダウンロード経路。

`HttpFetchPolicy` / `PublicNetworkPolicy` は Web 取得用に「loopback 以外・site-local 以外・link-local 以外・multicast 以外」を強制している。
`sqlite-vec` だけがこのポリシーを通らない。

**推奨**: 次の4点。

1. `BodyHandlers.ofInputStream()` で上限付き読み取り（例: 64 MiB）。
2. `HttpRequest.timeout()` と `HttpClient.connectTimeout()`。
3. リポジトリ内に既知バージョンの checksum を固定し、manifest と独立に照合。
4. `PublicNetworkPolicy` を適用、または `release-base-url` を既定で固定し設定変更を明示的にする。

## 秘密情報の扱い

- `.gitignore` に `credentials.bin`、`credentials.lock`、`credentials-*.tmp` を登録。
- `application.yaml` の秘密はすべて環境変数参照（`${REI_...}`）。ハードコードされたキーは見つからなかった。
- `BackgroundCommands` は出力前に `CredentialRedactor.redact(...)` を通し、制御文字を除去している。
- `logback-spring.xml` でファイル出力を設定。Slack SDK と Java-WebSocket は `OFF`。

**注意（F-12）**: `dev.mikoto2000.rei.bluesky` だけ既定 `DEBUG`。他を OFF にしている理由（生ペイロード回避）と矛盾する。

## Native Client の認証情報保護

`client/src-tauri/src/infrastructure/storage.rs` の `EncryptedVault` は次を使う。

| 要素 | 実装 |
| --- | --- |
| 暗号 | XChaCha20-Poly1305 |
| 鍵導出 | Argon2（`Argon2::default()`） |
| ファイル形式 | `REIVAULT`(8) + salt(16) + nonce(24) + 認証付き暗号文 |
| 平文の消去 | `Zeroizing` |
| パスワード最小長 | 12 文字 |
| 秘密の `Debug` | `[REDACTED]` |

**評価**: 暗号プリミティブの選択は妥当。`reqwest` も `rustls-tls` で OpenSSL に依存しない。

一方で、Java 側の `ProtectedCredentialStore` と比べると防御が非対称（F-20）。

| 観点 | Java `ProtectedCredentialStore` | Rust `EncryptedVault` |
| --- | --- | --- |
| 排他 | JVM `ReentrantLock` + OS `FileLock` | **なし** |
| ファイル権限 | ディレクトリと lock/host-id の symlink を拒否 | **明示なし** |
| 読み込み上限 | 1 MiB | **なし** |
| 平文の消去 | `Arrays.fill` | `Zeroizing` |
| 一時ファイル | `createTempFile` + `ATOMIC_MOVE` | `NamedTempFile` + `persist` |

**推奨**: Rust 側にもロック、0600、読み込み上限を入れる。

## Tauri のコマンド面

`native.rs` は 40 弱の `#[tauri::command]` を登録する。

- `vault_unlock` と `credential_set` は、パスワードと API キーを IPC 境界で平文の文字列として受け取る。Tauri の IPC は同一プロセス内だが、フロントエンドの JS ヒープに秘密が載る点は意識しておくべき。
- `tauri.conf.json` の CSP は `connect-src ipc: http://ipc.localhost` に限定され、任意ホストへの接続を許さない。HTTP 通信は Rust 側の `reqwest` が担うため CSP の制約外だが、`ServerProfile::new` が scheme を `http` / `https` に限定し、userinfo・query・fragment を拒否する。
- `HttpReiClient` は `redirect(Policy::none())`、`connect_timeout(10s)`、JSON 応答に 1〜64 MiB の上限を設定している。**妥当**。

## 未確認

- 実際の API キー強度・運用時のローテーション手順は文書化されていない。
- `rei.web.bind-address` の既定は `127.0.0.1` で妥当だが、`0.0.0.0` に変更した場合の警告がない。
- TLS 終端はアプリ外の前提。README にその旨の明記はない。
