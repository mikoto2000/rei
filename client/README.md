# Rei Client — Live Activity / Session History

Tauri 2 + React + TypeScript + Rust の Run / Conversation クライアントです。Windows を主な検証対象とし、共通コアとレスポンシブ UI を Android / iOS と共有します。

## ライブ Activity

イベントは Shell と同様の小さなテキスト行で表示します（例: `→ readFile`、`✓ readFile 18 ms`、`[llm] …`）。枠・太字見出し・大きな余白は使わず、長い行は画面幅で折り返します。

Tool の開始・完了・失敗ログは1段（1rem）インデントします。折り返した行も同じ位置に揃います。

Run の SSE を Rust の Projection で処理し、本文と Tool、LLM timing、Skill、Stagnation、Working Set の変化、Thinking の状態を出力順にチャット内へ表示します。本文の途中に届いたイベントはその位置に挿入し、Tool の開始と完了も別々の行として残します。Desktop / Mobile とも同じ時系列表示です。Shell と同じ semantic AgentEvent を使いますが、Shell の表示文字列は API にしません。Java の WebApiEventMapper が外部 DTO の項目選択と機密情報の除去を行います。

Activity はメモリ内のライブ Run 状態だけです。アプリ再起動後や過去 Session の取得で復元するのは User / Assistant の Turn 履歴のみで、イベント履歴の永続化は行いません。ReplayBuffer は切断時の再接続用です。詳細は [設計・イベント対応表](../docs/native-live-agent-events.md) を参照してください。

## 開発

必要なもの:

- Node.js 22.12+（検証: 24.11.1）、npm
- Rust stable / Cargo（検証: 1.91.1）、Windows MSVC Build Tools / Windows SDK / WebView2
- Android: Android Studio、SDK、NDK、JDK、`ANDROID_HOME` / `NDK_HOME`
- iOS: macOS、Xcode、Apple の署名環境

リポジトリの `client` ディレクトリで実行します。

```powershell
npm ci
npm run tauri -- dev

# Rust core（WebView がなくても実行可能）
cargo test --manifest-path src-tauri/Cargo.toml
npm test
npm run test:e2e
npm run typecheck
npm run lint
npm run format:check
cargo fmt --manifest-path src-tauri/Cargo.toml -- --check
cargo clippy --manifest-path src-tauri/Cargo.toml --all-targets --features native -- -D warnings

# Web assets + desktop executable
npm run tauri -- build --debug --no-bundle
# Release executable
npm run tauri -- build --no-bundle
```

Windows 実行ファイル: `src-tauri/target/debug/rei-client.exe`。通常の `cargo build` は開発用 URL を利用するため、配布可能な assets 埋込みビルドには Tauri CLI を利用します。インストーラー作成・署名は今回の対象外です。

Playwright はインストール済み Chrome を使います。`e2e/fixture.html` は command/event 境界を置き換えた UI テスト専用の入口で、production bundle に含みません。HTTP/SSE の結合テストは Rust の Axum mock server で行います。UI テストを実サーバーとの E2E とみなさないでください。

Windows の制限付き環境で Cargo の incremental cache rename が拒否される場合は、そのプロセスのみ `$env:CARGO_INCREMENTAL='0'` として再実行できます。Playwright の子プロセス終了には通常のローカル実行権限が必要です。

## 接続と操作

1. Settings で12文字以上のパスフレーズを指定し、Credential Vault を作成／解錠します。パスフレーズは保存しません。次の起動時も必要です。
2. Server の名前、base URL、API Key を登録します。API Key は入力欄から command に一度渡し、直ちに欄をクリアします。Rust から取得し直す機能はありません。
3. Test Connection で `Server reachable`（health）と `Authentication OK`（projects）を別々に確認します。
4. 新しい会話で server / project を選択して送信します。サーバー側 path は表示専用であり、API には project ID だけを送ります。
5. 最初の送信で session が関連付き、project は固定されます。会話を再選択すると同じ session で続けます。
6. Conversations はサーバーの Session 一覧です。project filter・追加読込み・更新に対応します。選択時に詳細と過去 Turn を取得し、同じ session で再開します。404 は自動再送せず、明示的に新しい会話へ進めます。409 は詳細を再取得してエラーを表示し、POST を再試行しません。
7. Active Runs から会話・Run を選択できます。Stop は必ず選択した server ID / run ID を指定します。

サーバーは LAN / VPN 上で利用し、HTTPS を推奨します。インターネットへの直接公開を前提としていません。Windows ではローカル開発向け HTTP も可能ですが、API Key を平文で送るため、信頼できるネットワークでのみ使用してください。認証付きリダイレクトは追従しません。

server URL の変更・削除は、この起動中に関連する会話が開かれていると拒否します。接続先の取り違えを防ぐ既存の制約です。必要な場合は Run の終了を確認し、アプリを再起動して設定を変更してください。サーバーの Session を削除する機能はありません。

## セキュリティと保存

- **Secret**: Rust `EncryptedVault`。Argon2id による鍵導出、ランダム salt、更新ごとのランダム nonce、XChaCha20-Poly1305 による認証付き暗号化。秘密値と導出鍵は zeroize します。パスフレーズを忘れると Vault を復旧できません。
- **Persistent app data**: app data directory の `app.json`。ServerProfile / credentialRef、selected server、通知設定だけを JSON repository に保存します。書込みは一時ファイルから atomic replace。API Key や会話本文は含みません。
- **Runtime**: RunManager、stream task、sequence cursor、Projection、再接続状態。アプリ終了時に失われます。サーバーの Run 自体はアプリ終了で cancel しません。

Stronghold を第一候補として試しましたが、依存する libsodium のダウンロードホストをこの環境から解決できず、RustCrypto による portable encrypted vault adapter を採用しました。秘密ストレージは `CredentialStore` / `CredentialFactory` で交換可能です。独自暗号アルゴリズムは実装していませんが、この Vault の外部セキュリティ監査は未実施です。

**保存済み API Key は WebView に返しません。** 入力時の一時的な password DOM / command 引数だけが例外です。React state、localStorage、sessionStorage、IndexedDB、通常設定への保存はありません。Rust の Secret は Debug を redaction し、Serialize を実装しません。HTTP / IO の生エラー、レスポンス本文、秘密を含み得る URL は UI error に流しません。

## 構成

```text
React feature components
  → src/tauri/commands.ts (typed boundary)
  → native.rs (Tauri command / DTO)
  → Application / SessionHistoryService / ConversationService / RunManager
  → ReiClient / CredentialStore / Repository / NotificationPort
  ← HTTP-SSE / EncryptedVault / JsonRepository / native notification adapters

Rei Server SSE
  → Rust HTTP bytes → SseParser → Projection → RunView
  → rei://run-state → React
```

composition root は `infrastructure/composition.rs` と `native.rs` です。application は具象 HTTP / ファイル / Tauri plugin を参照しません。domain は OS 固有処理に依存しません。Run は `(serverId, runId)` で識別するため、複数 server で同じ run ID が現れても衝突しません。Conversation ごとに多重送信を防ぎ、別 Conversation の Run は並列実行できます。

Rust → React events は `rei://run-state` と `rei://connection-state`。Projection snapshot に revision を持たせ、遅れて届いた command snapshot が新しい event を上書きしないようにします。Web API event を React で reduce しません。

## SSE

- Bearer 認証は Rust HTTP adapter で付与します。WebView の EventSource / fetch / plugin-http は使いません。
- 正常に検証・適用した event の numeric ID を Run ごとに保持し、`Last-Event-ID` として再送します。global sequence は飛びを許容します。
- heartbeat は cursor を変更しません。duplicate / older sequence は二重適用しません。
- backoff は 1 / 2 / 4 / 8 / 15 / 30 秒、最大30秒です。jitter は未追加です。
- UTF-8 の byte 分割、CR / LF / CRLF、複数行 data を処理します。巨大／不正フレームで無制限にバッファを拡大しません。
- terminal event で stream を閉じます。通常 EOF / 切断 / 45秒間 bytes が届かない場合は再接続します。
- 409 replay gap、または壊れた event で失われた内容は復元したことにせず、`incomplete` を保持します。gap 後は Run status を backoff 付きで poll して terminal を回収します。
- 長い再接続失敗中にも Run status を確認し、terminal を回収した場合は incomplete とします。手動 GET で terminal を先に回収した場合も同様です。
- 401/403 や未知 Run の SSE は無限 retry せず CLOSED + application error とし、資格情報の修正後に UI で再接続できます。
- terminal 通知は Run ごとに一度だけ。通知拒否・送信失敗で Run 処理は失敗しません。

## 実際に利用する WebApiEvent

サーバーの `AgentEventType.java`、`WebApiEventDto.java` と対応 Payload record を確認して実装しています。

| Event                      | 利用する payload                                |
| -------------------------- | ----------------------------------------------- |
| `agent.run.started`        | status RUNNING                                  |
| `agent.run.completed`      | status COMPLETED                                |
| `agent.run.failed`         | status FAILED、安全な汎用 failure 表示          |
| `agent.run.cancelled`      | status CANCELLED                                |
| `message.delta`            | messageId, delta                                |
| `message.completed`        | messageId, role, text（delta と重複させず確定） |
| `tool.started`             | toolCallId, toolName, argumentsSummary          |
| `tool.completed`           | toolCallId, toolName, resultSummary             |
| `tool.failed`              | toolCallId, toolName（FAILED 表示）             |
| `working_set.item.added`   | itemId, kind, identifier, path                  |
| `working_set.item.removed` | itemId                                          |
| `heartbeat`                | cursor を更新しない                             |

その他の valid v1 event は無視して cursor のみ進めます。未知 event でクラッシュしません。Working Set の正式な名前は `working_set.item.*` です。Tool Activity / Working Set は両方実装済みです。ツールの生のエラー内容は表示しません。

## モバイル

```powershell
# Android Studio で SDK / NDK を導入し、実際の環境に合わせて設定
$env:ANDROID_HOME='C:\Users\YOUR_USER\AppData\Local\Android\Sdk'
$env:NDK_HOME="$env:ANDROID_HOME\ndk\YOUR_NDK_VERSION"
npm run tauri -- android init
npm run tauri -- android dev
npm run tauri -- android build
```

生成される `src-tauri/gen` はローカル platform project として扱います。Android / iOS の app icon assets と `mobile_entry_point` を備えています。iOS は macOS 上で `npm run tauri -- ios init` / `ios build` を実行します。

Android の cleartext 制約を WebView 任せにせず、native HTTP adapter も Android / iOS では **HTTPS 必須**にしています。manifest へ一律 `usesCleartextTraffic=true` を追加しません。自己署名証明書の検証無効化も行いません。LAN / VPN でも端末が信頼する証明書を用意してください。

この環境では Android NDK / clang がなく、Android init と aarch64 cross-check が停止しました。Android APK と iOS 実機動作は未検証です。モバイル OS がアプリを suspend した間の常駐 SSE やバックグラウンド通知配信は保証しません。

## 範囲と制約

- Phase 1: server / credential / health-auth test / projects / chat / Run tracking / SSE / terminal / reconnect / replay / gap recovery / cancel / multiple runs。
- Session History: server Session list / project filter / opaque cursor pagination / detail / persisted Turns / authoritative resume。既存の project lock / RunManager / SSE / Tool Activity / Working Set / responsive UI と統合。
- 会話 metadata / transcript は永続化しません。再起動後は Session API から再取得します。active Run の自動復元とオフライン履歴は未対応。旧 app.json の conversations は読込み時に無視し、次の設定保存時に除去します。既存ファイルを起動時に強制書換えはしません。
- 実 Rei Server の API Key を使った live 接続、OS ごとの実通知、Android / iOS 実機は未検証。mock HTTP/SSE の結合テストと browser UI テストは別に実施しています。
- Phase 3 以降の search / summarize / image / briefing / feed / reminder / memory / interest / profile / skill UI は実装しません。

テストの Red → Green 記録は [TDD.md](TDD.md)、今回の検証結果は [VALIDATION.md](VALIDATION.md) を参照してください。

## Session History の契約

`session_list` / `session_get` / `session_turns` は専用 UI DTO を返し、`session_open` は詳細を検証して一時的な会話ハンドルを返します。HTTP DTO は adapter 内部、domain は日時を DateTime として保持し、UI は ISO 8601 文字列を受け取ります。履歴は React → command → application service → ReiClient のみで取得します。

limit は既定50、1〜100。cursor は解釈せず URL encode して送信します。一覧はサーバー順、Turn は createdAt 昇順を保持して下へ追加します。重複は sessionId / runId で排除し、警告は件数だけです。server / project / session 切替は generation を進め、古い応答を捨てます。refresh は cursor をリセットし、失敗時は既存表示を未同期と明示して保持します。

既存会話の送信前に Session 詳細を再取得し、その projectId / sessionId で POST します。新規会話は sessionId を送らず、サーバーの採番結果を使います。受理後と terminal 時に一覧・履歴を更新し、delta ごとの再取得はしません。保存済み Turn とライブ Run は runId で統合し、terminal の保存済み回答を優先しながら Tool Activity を残します。
