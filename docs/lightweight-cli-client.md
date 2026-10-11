# 軽量 CLI 移行の実装記録

現在の変更は Phase 1 の基盤のみであり、依頼された Phase 1〜4 の完了ではない。
通常起動を新 CLI に切り替える前に、永続冪等性・Session 排他・コマンド互換性・Windows の独立寿命を実装して検証する必要がある。

## 調査時点

開始時の `main` / `origin/main` はともに `cb19ede4b0cf7d651ecd6abbd44abaa4e6bbe9c6`。
未コミット変更なし。既存 worktree は存在するが、本作業用には作成・変更・削除していない。
作業ブランチは `feature/lightweight-cli-client`。

## 現行コードの調査と設計への反映

| 項目 | 確認した実装 | 設計への反映 |
| --- | --- | --- |
| 起動 | `ReiApplication.main` → `StartupOptions.parse` → `SpringApplication.run` → Shell | mode を Spring より前に解析。Shell の実行経路を保持 |
| 設定 | `ExternalConfigSupport` / `ReiDataDirectory` | 既存 `REI_DATA_DIR` / OS 標準ディレクトリ / `rei.data-dir` を維持 |
| Maven | ルートは Spring Boot の単一 jar、Java 25 | CLI モジュール分離は未実装。既存クラスの大量移動はしない |
| 入力 | `ReiLineReaderFactory` / `UserInputParser` / `UserInputService` | 新 CLI は同じ入力解釈・複数行・paste を再利用予定。未知の slash は送信禁止 |
| 補完 | `core.completion` は JLine / picocli / Spring に非依存 | 独立 module へ抽出可能。Shell adapter と Backend 候補取得は分離予定 |
| Web 有効化 | `WebApplication.configure` が `REI_API_KEY` の有無で確定 | server は既存 key がなければ起動前に拒否。資格情報生成なし |
| 認証 | `SecurityConfig` / `ApiKeyAuthenticationFilter` | Instance API にも既存 Bearer 認証を適用。health は識別に使わない |
| 排他 | `StorageMigrationConfiguration.StartupGate` → `StorageMigrationCoordinator` | Backend 自身が OS lease を取得してから Migration。lease は終了まで保持 |
| Run 作成 | `ChatSubmitService` → `SessionLifecycle` → `RunRegistry` → `ConversationInputRouter` | Session 単位の永続 admission を全フロントエンドで共有する変更が必要 |
| Session | `SessionLifecycle` は repository monitor で admission を直列化 | 現在は同じ Session の Run を拒否する契約ではない。単純な controller Map では対応しない |
| Run 永続化 | `RunRegistry` は一部機能が有効な場合のみ DataSource を利用、owner PID / 開始時刻 / instance を記録 | CLI の冪等性には常時永続化と transaction 境界の設計が必要 |
| 再起動 | owner 喪失を `UNKNOWN` に変更、terminal metadata は30分保持 | 冪等性記録を Run retention とは独立して保持する必要がある |
| SSE | `SseController` / `SseBridge` / ReplayBuffer | 既存 `Last-Event-ID`、409 gap、terminal polling を再利用予定。POST 自動再送禁止 |
| Permission | `ApprovalController` は Project と request ID に限定して decision | Tool 実行・Resume と承認は別操作。Run / Session の対象確認が必要 |
| Checkpoint | `CheckpointController` / `PersistentCheckpointService` | 既存検証と reconciliation を維持。汎用実行 API は追加しない |
| Windows 起動 | `start.bat` は `JAVA_HOME` を優先して Backend jar を同じコンソールで起動 | 現状を detached backend と見なさない。独立コンソール・標準 IO・ログ・ペイン終了の検証が必要 |
| Native | `client/README.md` / `docs/native-web-api.md` | 現行 HTTP / SSE 契約を保持。Native の操作範囲も Shell 全機能とは異なる |
| 検証 | Surefire、integration / e2e / live タグ、複数 JVM Storage テスト | ACL は Windows の通常ユーザートークンで検証。実機 E2E と mock HTTP を区別 |

## 今回追加する起動・識別基盤

```powershell
# ソース変更後は配布 jar を更新する。
.\mvnw.cmd '-DskipTests' package

# 既存の設定済み API key を環境から渡す。ここでは新規 key を生成しない。
.\start.bat --mode=server

# 従来の全機能 Shell。Backend と Shell の寿命は共通。
.\start.bat --mode=legacy-shell

# 既定名の jar の置換が Windows で拒否される環境では、別名でビルドする。
.\mvnw.cmd '-DskipTests' '-Drei.build.final-name=rei-lightweight-foundation' package
java -jar target/rei-lightweight-foundation.jar --mode=server
```

移行中の引数なし起動は従来 Shell。`auto` / `client` は未実装として Spring 初期化前に明示エラー。
`server` は Shell を実行せず、既存 Spring shutdown hook と Web server により稼働する。
このモードを Windows Terminal ペインの終了から独立しているとは保証しない。
`server status` / `server stop` と detached 起動は未実装。

API が有効で loopback へ接続できる bind のとき、ApplicationReady 後に以下を公開する。

- `<data-dir>/.storage/storage-id`: データディレクトリの UUID。壊れている場合は勝手に再生成しない。
- `<data-dir>/.storage/backend-endpoint.json`: schema / instance / storage / PID / loopback URL / protocol / READY。
- `GET /api/v1/instance`: 上記と同じ識別情報。既存 Bearer 認証が必要。準備前は503。

メタデータに秘密情報を含めない。Windows ACL はファイル所有者だけに許可する。
一時ファイルを書き込み・force し、atomic move で公開する。非原子的な置換に fallback しない。
shutdown は自分の instance のファイルだけを削除する。異常終了では stale file が残り得る。
既存の明示的な非 loopback bind は変更せず、ローカル discovery を公開しない。

Launcher 用の OS lock probe は advisory であり、lease の予約・Backend への引き継ぎではない。
起動の最終的な可否は Backend の既存 Storage gate だけが決める。
ファイルの存在・PID・port・health 成功だけでは Backend 所有者と判断しない。

## 接続失敗の扱い

`REI_API_KEY` が未設定なら既存 key を設定して再起動する。無断の API 有効化、LAN 公開、資格情報保存は行わない。
401 は認証失敗、protocol の違いは互換性不一致、instance / storage / PID / URL の違いは所有者不一致として扱う。
OS lock が解放済みなら stale endpoint。接続不能でも OS lock を無視しない。
既存 Backend の起動中と、起動済みだが API が無効な状態を完全に区別する launcher は今後の実装対象。

## 残る Phase

| Phase | 状態 | 未完了の受け入れ条件 |
| --- | --- | --- |
| 1 | 一部実装 | Launcher / auto / client / readiness wait / status / stop / detached 起動 |
| 2 | 未実装 | CLI、Session 排他、永続 Idempotency-Key、HTTP / SSE 再接続、Cancel |
| 3 | 未実装 | 全コマンドの引数・補完・内部依存・API・権限の対応表、限定 API、永続履歴、互換性解消 |
| 4 | 未実装 | Windows Terminal 実機 E2E、独立寿命、ログ・配布・運用整備 |

Phase 2 は既存 `SessionRepository.accept` と Run 保存の transaction 境界を確認してから拡張する。
同じキーの異なる payload、期限切れ、受付後の障害を区別し、自動再実行しない契約を先にテストする。
Planning / Scheduler / child Run の意味を壊さないよう、会話 admission と delegation を区別する。

入力履歴は現時点で新規実装なし。新 CLI の履歴保存場所・削除方法・保持規則はまだ提供しない。
既存 Shell の入力履歴の動作は変更しない。

## テスト

```powershell
.\mvnw.cmd '-Dtest=BackendEndpointTest,BackendModeTest,BackendInstanceTest,InstanceControllerTest,BackendConnectionProbeTest' test
.\mvnw.cmd '-Dtest=ReiApplication*Test,StorageMigrationCoordinatorTest,StorageStartupGateTest' '-Dtest.excludedGroups=live' test
.\mvnw.cmd '-Dtest=WebApiIntegrationTest,SecurityConfigTest' '-Dtest.excludedGroups=live' test
.\mvnw.cmd test
cd client
npm test
npm run typecheck
cargo test --offline --manifest-path src-tauri/Cargo.toml
```

Windows の所有者限定 ACL を制限付きトークンで操作すると拒否されるため、ACL テストは通常のユーザー権限で実行する。
複数 JVM テストは OS ロックを実際に保持し、2つ目の起動拒否・DB未作成・強制終了後の lock 解放を確認する。
Windows の長い classpath は Java 引数ファイルで渡す。

参考: [既存 API と Native Client](native-web-api.md)、[補完](completion.md)、[Native README](../client/README.md)。

## 検証結果（2026-10-11）

- Java 既定の単体テスト一式: 2,522件、失敗0、エラー0、skip0。
- Shell / Storage / Web API の選択回帰: 100件成功。上記単体テストと重複するため合算しない。
- 実 HTTP Instance / 再起動 / shutdown の integration: 1件成功。
- 最終の追加テスト・既存 API 選択検証: 16件成功。新規13件（同時 JVM 起動を含む）と既存 API 3件。前の検証と重複するため合算しない。
- Native UI: 25ファイル、99件成功。
- Native TypeScript: `tsc --noEmit` 成功。
- Native Rust: 120件成功（HTTP / SSE / Session / credential 契約を含む）。
- 配布 jar: 既定名の repackage は通常ユーザー権限でも rename 拒否。`rei.build.final-name` による別名の Spring Boot jar はビルド成功。既定の配布名は変更していない。
- 別名 jar の実起動: legacy-shell help は exit0、未対応 client は exit2、API key 未設定 server は exit2。Storage が初期化されないことを確認。
- 最初の Red はクラス未実装によるコンパイル失敗。Windows ACL・一時 rename・loopback HTTP の sandbox 制限による失敗は、保護を緩めず通常ユーザー権限で再実行して成功。
- Windows の複数 JVM と ACL は検証済み。Windows Terminal ペイン終了、Ctrl+C、独立 Backend の寿命、複数 CLI 会話の実機 E2E は未実施。
- Live LLM / 音声デバイスの E2E は未実施。

既存 Shell のコマンド・入力実装を削除、無効化、縮小していない。
Run admission、LLM、Tool Permission、Scheduler、Native の既存エンドポイントの実装は変更していない。
追加した主要クラスは `BackendMode`、`BackendEndpoint`、`BackendEndpointStore`、`BackendOwnership`、
`BackendConnectionProbe`、`BackendInstance`、`BackendInstanceConfiguration`、`InstanceController`。
変更した既存クラスは `ReiApplication` と `StartupOptions`。

これらの成功をもって、軽量 CLI の必須受け入れ条件を満たしたとは扱わない。
