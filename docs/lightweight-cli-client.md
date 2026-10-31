# 軽量 CLI と単一 Backend

単一 Backend と独立 CLI を実装し、既存 Shell を維持した。RootCommand の47項目のうち、ユーザー指定で延期した voice を除く46コマンドを固定ドメイン API または CLI ローカル処理へ接続する。Windows Terminal の実2ペイン、専用ウィンドウの GUI close、ネイティブ JLine の対話型 ConPTY を検証した。[全47コマンドの対応表](lightweight-cli-command-matrix.md) と末尾の最新検証記録を参照。

main 統合で追加された `/chatgpt` を含む現在の登録は48項目。全項目の補完とヘルプを取得し、
`/chatgpt` の認証・利用枠操作と `/voice` の実行は従来 Shell を使用する。
Slack は明示的に有効化すると通常 Shell と専用 Backend のどちらでも接続する。
Shell を開かない常駐起動には `--backend` または `--mode=server` を使用する。

## main 統合の検証（2026-10-11）

Slack・ChatGPT 対応を含む main と統合し、日本語のコマンド説明を維持した。
外部返信待ちの Session は新規 Run に渡さず、所有プロセス終了後に同じ Run と receipt を引き継ぐ。
server の Slack 起動条件と返信待ちの保護をテストで再現してから修正した。

- Backend 全回帰: 5,010 件、失敗・エラー 0、既存の環境条件付きスキップ 2 件。
- CLI・launcher: 81 件（実 ConPTY を含む）。Native UI: 99 件、型検査成功。Native Rust: 120 件。
- Windows 実 Backend: 全48項目のヘルプ、二重送信 receipt、終了後の再接続と明示 stop。
- Windows Terminal: 実2ペインの独立性、専用ウィンドウの GUI close 後の Run 継続・完了と再接続。

検証ログは `target/main-merge-final-full.log`、`target/main-merge-terminal.log`、
`target/main-merge-native-*.log`、`target/main-merge-windows-*.log`。
Terminal ペインの stdin/stdout は fixture に接続し、対話キー入力は実 ConPTY で別途検証した。
検証用 JAR は `target/main-merge-package/rei-backend-main-merge.jar` に分けてビルドした。

プッシュ直前の main 更新（Slack の通常 Shell 対応）も追加統合し、変更範囲の
Slack／HTTP／起動／会話所有権の361テストを再実行して失敗・エラー0を確認した。
最新 JAR の Windows 起動・複数ペイン・GUI close も再検証した。
GUI close は並行実行で一度タイムアウトし、単独再実行で Run 継続・完了・再接続まで通過した。
追加の証跡は `target/main-merge-latest-slack-regression.log` と
`target/main-merge-latest-windows-*.log`（GUI の成功結果は `gui-retry.log`）。

## 構成と境界

```mermaid
flowchart LR
  L[rei launcher] -->|OS ownership + authenticated identity| B[Backend JVM]
  C1[CLI JVM 1: JLine / local selection] -->|HTTP / SSE| B
  C2[CLI JVM 2: JLine / local selection] -->|HTTP / SSE| B
  N[Native / Web client] -->|existing API v1| B
  B --> S[Storage lease / migration / SQLite / Run / tools]
```

Backend だけが Storage、Migration、LLM、MCP、Tool Permission、Run、Session、Checkpoint を所有する。CLI は Spring、SQLite、provider SDK を依存に持たず、Backend の会話データを直接読まない。launcher が discovery と OS lock の状態を読む。CLI の Project/Session 選択は process ごとの local state であり、Shell や別 CLI の選択を変えない。

`terminal/pom.xml` は既存ルートビルドとは独立した reactor。`contract` は discovery・固定 catalog・純粋引数文法の Java クラス、`completion` は `core.completion`、`cli` は JLine + Java HttpClient、`launcher` は OS 起動を扱う。既存 Backend クラスの大量移動は行っていない。CLI は文字・通常の端末出力を中心とし、全面再描画 TUI は使用しない。

## ビルドと起動

JDK 25、既存の Backend 設定、設定済みの `REI_API_KEY` が必要。キーを launcher が生成・保存することはない。データ保存先は既存 `REI_DATA_DIR` / `rei.data-dir` と OS 標準ディレクトリの規則に従う。

```powershell
# リポジトリのルートから実行
.\mvnw.cmd -DskipTests package
.\mvnw.cmd -f terminal/pom.xml package

# 通常の launcher 起動は auto。Backend を開始または既存 Backend に接続
.\rei.bat --project F:\project\example
.\rei.bat --mode=client       # 接続のみ。Backend がなければエラー
.\rei.bat --mode=server       # detached Backend を準備して launcher は終了
.\rei.bat server status
.\rei.bat server stop --yes   # 全 client に影響する停止の明示指定
.\rei.bat --mode=legacy-shell # 従来 Shell 全機能
.\rei.bat --mode=legacy-shell -- --help
```

`rei-cli.bat` は同じ launcher、Unix 向けは `sh ./rei.sh` / `sh ./rei-cli.sh`。Unix detached 起動は `/usr/bin/setsid` が必要で、macOS の service 管理は未実装。既存 `start.bat` / `start.sh` や Backend jar の直接起動は引き続き従来 Shell が既定。Backend jar 自体に `auto` / `client` を渡しても launcher にはならず、Spring 初期化前にエラーとなる。

Windows で稼働中の既定 jar を置換できない場合は、既存 process を止めず別名・別ディレクトリでビルドできる。

```powershell
.\mvnw.cmd '-Drei.build.directory=target/backend-package' '-Drei.build.final-name=rei-backend' -DskipTests package
.\rei.bat --backend-jar target/backend-package/rei-backend.jar --mode=server
# 以降も同じ --backend-jar を指定できる
```

launcher の `--project` は Backend の専用 Project 登録 API を使う。全機能 Shell の入力を新 CLI に汎用転送する API は作っていない。Shell は Backend と同じ JVM・同じ寿命であり、API 無効時は `--mode=legacy-shell` を使う。既存 Shell が Storage を所有する間に新 Backend を二重起動しない。既存 Shell を通常終了し、既存 API key を設定してから launcher を利用する。

Windows Terminal では、同じ `REI_DATA_DIR` と `REI_API_KEY` を設定した別ペインでそれぞれ `rei.bat --mode=client` を実行する。各 CLI の `/project select` と `/session switch` は独立する。実 Windows Terminal の split-pane、先行ペイン終了、専用ウィンドウの GUI close を含む E2E を実施済み。

## discovery・起動・停止

`<data-dir>/.storage/storage-id` はデータディレクトリの UUID、`backend-endpoint.json` は schema / instance / storage / PID / literal IPv4 loopback URL / protocol / READY を保持する。所有者だけの ACL / mode 600 と atomic move で公開し、秘密を含めない。壊れた UUID を勝手に再生成しない。protocol は2で、HTTP API の既存 `/api/v1` routes は維持する。

接続には OS lease の所有状態、永続 storage ID、認証済み `GET /api/v1/instance` の全 identity 一致が必要。PID、ファイルの存在、port、health 成功だけでは接続しない。redirect と proxy に資格情報を渡さない。起動中は readiness を最大90秒待つ。競合 launcher の最終 admission は Backend の Storage gate が決め、lease を取得するまで Migration しない。

Windows は `CreateProcess` の DETACHED_PROCESS / NEW_PROCESS_GROUP / BREAKAWAY_FROM_JOB を使用し、標準 IO と process handle を継承しない。job が breakaway を許可しない場合は明示エラー。コンソールと寿命を共有する起動には fallback しない。ログは `<data-dir>/logs/backend.log`。

停止は `server stop --yes` で、認証・identity を再確認して `POST /api/v1/instance/stop` を呼び、lease 解放を待つ。任意 PID を kill しない。CLI の `/exit`、Ctrl+D、Ctrl+C、接続喪失は Backend や他 CLI の Run を停止しない。Ctrl+C は自身の表示購読を切断し、Run cancellation は `/cancel RUN_ID` で明示する。

## Session admission と冪等受付

schema 6 の `conversation_admissions` / `chat_receipts` を、既存 Migration のバックアップ・検証・OS lease の下で追加する。Session metadata、canonical Run、Session lease、receipt を一つの Storage transaction で受付確定し、その後 dispatch する。全フロントエンドが共有する SessionLifecycle と ConversationInputRouter に適用する。

同じ Session に実行中の会話 Run は最大1つ。HTTP の新規独立 Run は409で target Project/Session/Run ID を返す。別 Session は既存 Project queue の access 規則に従って並行可能。既存 Shell の EXCLUSIVE 入力 queue は保持し、待機 Run は先行 Run の runner cleanup が終わってから Session lease を取得する。cancel の terminal event が届いただけでは実行中 lease を解放しない。

`POST /api/v1/chat` の任意 `Idempotency-Key` は `[A-Za-z0-9._:-]{1,128}`。Project、要求した Session（null を含む）、message、正規化 mode を fingerprint に含める。同じ key / payload は元の Run/Session を返し、再 enqueue しない。payload 変更は409。`GET /api/v1/chat/receipts/{key}` で受付照会できる。HTTP 応答の欠落、timeout、受付後の5xxでは CLI は receipt を一度照会し、POST を自動再送しない。照会できない場合は key と「受付不明」を表示する。

receipt 照会期間は7日。期限後は410で再実行しない。key hash の tombstone は再利用しない。admission / receipt はそれぞれ100,000件が上限で、容量超過は受付を拒否する。自動削除・再利用の運用は未実装。raw key、API key、message 本文は receipt テーブルに保存しない（通常の Session title/turns には会話が保存される）。owner PID / 開始時刻 / instance を検証し、再起動で owner を失った非 terminal Run は UNKNOWN。副作用を検査し、既存 Checkpoint の明示 Resume を使う。

## CLI の入力・コマンド

プロンプトは従来 Shell と同じく `[プロジェクト名] [N running]` と時刻・モデル名の2行で表示する。
Run 数は選択中の Session に限定せず Backend 全体の active executions を約1秒間隔で取得し、入力待ち中にも更新する。
取得失敗時や件数フィールドのない旧 Backend では `? running` と表示する。
通常入力、`/chat` などのコマンド、複数行入力、`/paste` は Shell と共通の時刻付き User 枠で表示する。
Shell と同じく `/project` と `/sh` の入力枠は省略する。
Run・LLM・ツール・Skill 選択・Subagent に加え、ファイル変更、Working Set、コンテキスト予算、
進捗・停滞、バックグラウンド処理、話題通知なども Shell の表記に揃える。
Shell の既定 summary 設定で非表示になる監査イベントは CLI でも表示しない。イベントの JSON fallback は使用しない。
話題通知の抑制はイベントの発生時刻を使い、再接続・遅延表示でも30分の間隔を保つ。
API が非公開とする内部根拠・検索語・任意の例外詳細などの公開範囲は維持し、公開される項目で通知を表示する。
Receipt と Run ID の受付表示は維持する。

JLine の入力編集、履歴、prompt、completion、末尾 `\` の複数行入力と `/paste`（単独 `.` で完了）を用意した。Tab は cached Project/Session ID と共通 `core.completion` の client-local path 補完を使う。Tab 中に HTTP / Storage 読み取りを実行しない。Shell と同じ純粋 tokenizer とパス補完を共有する。Root 宣言の引数・options・排他 group・alias・既定動作は起動時に取得したメタデータに保持し、Tab はキャッシュだけを使用する。

全コマンドの詳細は [対応表](lightweight-cli-command-matrix.md)。`/help COMMAND [SUBCOMMAND]` で Picocli 形式のヘルプを表示する。裸の親コマンドは既存 Shell と同じくヘルプを表示し、既定動作を持つコマンドはその動作を実行する。

| 区分 | コマンドと操作 |
| --- | --- |
| 選択・会話 | `/project add/remove/list/cd`、`/session new/switch/resume/end/show/list`、`/chat [--mode ACCESS] [--run RUN_ID] PROMPT`、`/mode auto/normal/conversation/status` |
| 実行・履歴 | `/cancel` は選択 Project の停止、`/runs` は全 active 表示。`/history` の Session 一覧、旧会話の list/show/search と全 options |
| 診断・設定 | `/timing`、`/doctor`、`/config path/init [--force]`、`/model`、`/models`、`/profile path/summary/chart/mermaid` |
| 永続制御 | `/storage` の全 retention/maintenance、`/goal`、`/attention`、`/timer`、`/dependency`、`/approval`、`/checkpoint`、`/resume`、`/work` |
| 調査・生成 | `/search` の全 options、`/paper`、`/material-review`、`/agent`、`/document`、`/repair`、`/image [generate]`、`/summarize [URL]` |
| ナレッジ | `/embed add/search/list/delete`（glob は CLI で展開）、`/reflection` と lesson 操作、`/skill`、`/subagent`、`/memory`、`/sleep` |
| 外部連携 | `/schedule` は Google Calendar、`/task` は Google Tasks。OAuth・一覧・追加・完了・削除は専用 Run と SSE を使い、認可待ちも CLI に通知する |
| 日常情報 | `/activity` の全宣言操作、`/feed` の全宣言操作、`/briefing today`、`/reminder add/list/delete`、`/bsky`、`/interest` |
| ローカル | `/sh` は CLI の選択ディレクトリで対話 Shell を開き、終了後 JLine に戻る。`/paste`、`/help`、`/exit` |
| CLI 拡張 | `/watch RUN_ID`、`/receipt KEY`、`/run-mode`、`/input-history off/on`、`/tasks`（Task Manager）、`/project select/register` |
| 延期 | `/voice` は明示未対応。音声は既存 Shell で利用する |

未知の slash・subcommand・option は明示エラーとなり、LLM に送信しない。任意 URL・service・shell・reflection の汎用実行 API は用意しない。ドメイン結果には既存 Shell の formatter または CLI 専用の固定表現を使い、既存 Web/Native の JSON 表現を維持する。CLI の Run/receipt 表示は実行識別のための追加表示。

SSE は sequence を処理し `Last-Event-ID` を付けて最大3回再接続、重複は捨てる。接続待ち中の detach も古い購読を復活させない。409 gap は output incomplete と表示し、Run status を最大60秒 polling、terminal 後に保存 turns を照会する。通常のストリーム・JSON 応答は1MiBに制限。確認済み capture 原本表示は control escape 後の上限を8MiBにする。resume / POST / cancellation を SSE reconnect と連動させない。

Goal create は現在選択した registered Project/Session を Backend で検証し、WEB の RequestSource で既存 GoalLoopService に渡す。`--file` と `--sha256`、または `--criteria-json` の一方を指定し、任意の `--completion-json`、`--max-runs`（既定3、1..10）、`--max-llm-calls`（既定20、1..100）を使用する。completion は `--completion-json` / `--proof-json` のどちらか一方。wait は `--dependency-id` と任意の `--wait-reason`、wait-activate/resume/cancel は観測した `--wait-version` が必要。重複 option、JSON の重複キー・末尾・過大入力は拒否する。

## 入力履歴と秘密

入力履歴は Shell と同じくプロジェクトごとに切り替わり、↑・↓ キーで現在のプロジェクトの入力を呼び出す。保存先は `${user.home}/.rei-cli/history/projects/<Project ID の UTF-8 十六進表記>/cli-UUID.history`。プロジェクト未選択時と旧版の履歴は従来の `history/cli-UUID.history` に残し、プロジェクト別の履歴へ混ぜない。Backend データディレクトリとは別で、各 CLI が自身のファイルだけを exclusive lock して書く。起動・切替時は対象プロジェクトの最新の終了済みファイル一つから安全な行だけ読む。他の動作中 CLI の履歴を読み書き・merge しない。最大1,000行・1MiB、owner-only ACL / 600。過去ファイルの自動掃除は未実装。

`--no-history` または `/input-history off` で入力履歴を保存しない。認証用 `REI_API_KEY` の実値、Bearer / Authorization / api-key / password / secret などの行と複数行は除外し、同じ規則を JLine の memory history にも適用する。任意の文章が秘密かどうかを完全に推定はできないので、機密入力時は明示 off にする。API key は metadata や client 設定に保存せず、CLI 出力でも既知の key を redact する。

履歴の削除は全 CLI を閉じてから、`$env:USERPROFILE\.rei-cli\history` 配下にある対象 `cli-UUID.history` を確認して削除する。Backend の `.storage` や SQLite ファイルを削除しない。履歴を git・ログ収集に含めない。

## 接続失敗への対処

- API key 未設定: 既存 key を環境へ設定するか legacy-shell を使う。launcher は生成しない。
- 認証失敗 / protocol 不一致 / identity 不一致: 接続・置換を拒否する。設定と Backend バージョンを確認する。
- STARTING_OR_LEGACY_SHELL / readiness timeout: 同じ data-dir を使う既存 Shell、Backend ログ、Storage lease を確認する。所有中の lock を削除しない。
- stale endpoint: lease が無ければ起動候補は作れるが、最終 ownership は Backend が取得する。PID を根拠に別 process を停止しない。
- BREAKAWAY エラー: job 制限を確認する。独立 OS service として起動する運用が必要な環境では、通常 child に fallback しない。
- 同じ Session の409: 表示された Run を確認し、別 Session を選ぶか既存 Run へ明示追加指示する。
- receipt 不明 / 期限切れ / UNKNOWN: 自動再送せず、保存履歴・Run・Checkpoint と副作用を検査する。

## テストと実装状況

```powershell
.\mvnw.cmd -Pfull test                       # live の外部実行は除外
.\mvnw.cmd -f terminal/pom.xml package
.\scripts\Test-LightweightWindows.ps1       # PowerShell 7 / Windows / JDK25
# E2E の既定 Backend jar は target/backend-package/rei-backend.jar
# 任意のビルド済み jar は -BackendJar ABSOLUTE_PATH で指定
```

Windows E2E は毎回 repository の target 以下に UUID の隔離 root を作り、fixture key と provider の到達不能 loopback URL を子 process にだけ渡す。既存 Backend を止めず、認証・identity を確認して fixture Backend を停止する。外部 LLM 推論の成功を検証するテストではない。

| Phase | 現状 |
| --- | --- |
| 1 | gate/discovery・永続受付・排他・停止を実装。実複数 JVM の lease 競合と migration 前の異常終了、実 HTTP の同時再送・restart・cleanup 中排他を検証 |
| 2 | 独立 modules、JLine、local selection、共通 tokenizer/補完、独立履歴、HTTP/SSE、receipt 照会、明示 cancel を実装・検証 |
| 3 | 全47項目を調査し voice 以外を接続。直接宣言の全サブコマンドのヘルプを Picocli と照合。paper/material-review/doctor は既存の純粋 request parser を共有 |
| 4 | Windows worker の親強制終了後の独立寿命に加え、実 Windows Terminal の2ペインで同時 auto 起動、先行 CLI/ペイン host 終了後の同じ Backend と別 CLI の継続、Run RUNNING→COMPLETED、SSE 受信、Session 操作、明示停止を確認。CLI 標準入出力は pane host 内で redirect し、対話型 ConPTY で `/sh` 入力・cwd・CLI 復帰・Ctrl+C 後継続を追加検証。専用 Terminal ウィンドウの GUI 強制 close、両ペイン終了後の Run 完了・新 CLI 再接続・明示停止も確認。47 コマンドの help を実 Backend/CLI でも検証（voice 実行は対象外） |

Native API の詳細は [Native/Web API](native-web-api.md)、全機能 Shell の利用は [README](../README.md) を参照する。最終テスト件数・commit は作業報告に記録する。外部アカウント・実 provider の認可/推論成功は隔離 fixture の検証とは区別する。

### 2026-10-11 の前回検証記録（最新結果は末尾）

| 検証 | 結果 |
| --- | --- |
| Backend `-Pfull test`（隔離 build directory） | 4,726件、失敗0・エラー0・skip 2。skip は既存の任意 benchmark 等であり、変更による無効化なし |
| 今回の Backend 対象回帰（-Pfull） | 62件成功。Migration・別 JVM lease・admission・Goal HTTP・モデル API。全 Backend 4,726件の結果は今回の追加前の前回検証 |
| CLI / launcher reactor | 41件成功。対話 ConPTY、ローカル Shell、Project add/cd、Project 記憶文法を追加。Windows worker の親 process 強制終了後の独立寿命、SSE Accept、PUT、モデル・Goal・Attention・Feed/Reminder/Interest文法を含む |
| Native UI | 25ファイル・99件成功、TypeScript typecheck 成功 |
| Native Rust | 120件成功（offline cargo test） |
| Windows 実 Backend / CLI jar | 成功。最初の CLI 終了後の readiness、同じ instance の別 CLI 接続、Project/Session/style、同時同 key の同 Run、再起動 receipt、2回の安全停止 |
| Windows Terminal 複数ペイン | 実 pane host で同時 auto 起動と Run/SSE 継続・完了・明示停止に成功。標準入出力 redirect を使用。別途、実 ConPTY にネイティブ JLine を接続した対話 Shell E2E が成功 |
| 外部 LLM・実 Google OAuth・音声 | 実アカウントでの認可/推論は未実施。音声は対象外。対話型 PTY は末尾の最新記録 |

TDD の Red/Green は admission の原子性・冪等性・期限・競合・Shell queue、CLI の受付不明・5xx後照会、秘密の入力履歴除外、Windows path 解釈、接続待ち中 SSE detach、応答スタイル分離、chat options、busy 対象表示で実施。全回帰の初回は4件失敗/エラーを検出し、旧 schema fixture の新テーブル取り残し、同一 Session 新契約の旧期待値、HTTP fixture の publisher 重複を修正して再実行した。

### 主な追加・変更 API

- `POST /api/v1/chat`: 任意 Idempotency-Key、同一 Session busy の409（Project/Session/Run ID付き）。既存キー無しの契約は維持。
- `GET /api/v1/chat/receipts/{key}`: 永続受付結果。未発見404、期限切れ410。
- `POST /api/v1/projects/register`: absolute existing directory の専用登録。任意 filesystem 列挙 API は追加しない。
- `POST /api/v1/sessions`: registered Project に空 Session を作成（201）。Run を起動しない。
- `GET/PATCH /api/v1/sessions/{id}/response-style`: Project 所有権を検証する応答スタイル専用操作。既存 SessionResponse の5 fields は変更しない。
- `POST /api/v1/instance/stop`: 認証済み instance/storage identity に限定した graceful stop（202）。
- `GET /api/v1/models`, `GET/PUT /api/v1/models/current`: 既存モデル一覧・選択サービスの専用 API。
- `POST /api/v1/projects/{projectId}/goals`: 所有 Session を検証した Goal 作成（201）。既存 completion/wait API も CLI から利用する。wait の任意 sessionId は選択所有権を検証し、従来の2項目 request は維持する。

実ペイン検証は Python 3 の標準ライブラリによる loopback 応答 fixture と PowerShell 7、Windows Terminal、JDK 25 を使う。ユーザーの既存 Terminal と Backend を操作せず、新しい2ペインと UUID の専用データ領域を作成する。fixture は外部 LLM にアクセスしない。

```powershell
.\mvnw.cmd '-Drei.build.directory=target/backend-package' '-Drei.build.final-name=rei-backend-panes' -DskipTests package
.\mvnw.cmd -f terminal/pom.xml package
pwsh -NoProfile -File scripts/Test-WindowsTerminalPanes.ps1
```

Backend JAR のビルド完了後に E2E を実行する。動作中の JAR を上書きしない。各 pane の WT_SESSION は異なるため同一値を要求しない。正常な host exit による pane の自動終了は Terminal profile の closeOnExit 設定に従う。`-GuiClose` では専用 UUID ウィンドウを UI Automation の WindowPattern.Close で閉じ、両ペイン終了後も Backend と Run が継続することを確認する。対話キー入力は別の実 ConPTY E2E で検証する。

主なクラスは `ConversationAdmissionStore` / `ConversationAdmissionMigration`、共有 `SessionLifecycle` / `ConversationInputRouter` / `RunRegistry`、`BackendClient` / `TerminalClient` / `ClientHistory` / `SseDecoder` / `RemoteCommands`、`Launcher` / `DetachedBackend`。既存 Shell の全コマンドは保持し、HTTP の同一 Session 独立 Run は依頼された排他契約に合わせて409となる。

### 対話 Shell と Project 記憶の追加検証

`/sh` は CLI の選択 Project でローカルの対話 Shell を起動し、終了後に端末属性と入力処理を復元する。`SHELL` と OS 既定値・PowerShell の `-NoLogo` は既存 Shell と同じ。Backend へ汎用 Shell 実行 API は追加しない。

`WindowsPtyTest` は [Microsoft の ConPTY 手順](https://learn.microsoft.com/en-us/windows/console/creating-a-pseudoconsole-session) に従って実 OS PTY を作り、ネイティブ JLine の使用を必須として検証する。選択ディレクトリを PowerShell で実際にファイルへ保存し、Shell 終了後と Ctrl+C 後に CLI コマンドが使えることを確認する。テスト用子プロセスだけをコンソールに接続し、終了時に PTY・process・pipe・attribute list を解放する。

追加 Backend 回帰は Project 登録解除、Project 所有権付き記憶 API、既存 Memory Shell、verified reflection の14件成功。他 Project の記憶は list に出ず、show/forget を拒否して内容を保全する。GET `/api/v1/projects/{projectId}/memory`、GET `/search`、GET `/{id}`、POST `/{id}/forget` は Shell と同じ記憶サービスを使用する。

`Test-WindowsTerminalPanes.ps1 -GuiClose` と `Close-WindowsTerminalFixture.ps1` は実 Windows Terminal の専用ウィンドウを GUI から閉じる。共有 Terminal プロセスの PID で終了を判定せず、生成した UUID タイトルと元のウィンドウハンドルを検証する。両ペイン終了、同じ Backend identity、Run の RUNNING → COMPLETED、新 CLI から履歴・Run の照会と Session 作成、認証済み明示停止まで成功。Close all の確認ダイアログが現れた場合だけ、その専用ウィンドウ内のボタンを操作し結果に記録する。

### Shell 互換の追加検証

履歴の Session ページング・旧 conversation 表示・検索 scope、profile の summary/path/chart/mermaid、timing、doctor、全 active Run 表示と Project 限定 cancel を専用 API に追加。履歴表示はリクエストごとの Project scope を使い、既存 Shell の選択を復元する。document/repair の復旧操作は明示ハッシュを検証し、paper/material-review/agent は既存の引数文法を共有して永続受付と Run キューへ接続する。

最新版 Backend の対象回帰49件、独立 CLI の全回帰51件が成功。通常の2ペイン終了と GUI 強制 close の両 E2E も最新版 Backend jar で再確認した。この件数は全 Backend 回帰とは別の対象検証。Shell/Web が別 ProjectRegistry インスタンスで同じファイルを同時更新する競合を再現し、ファイル単位の Backend 内ロックを追加。48 Project の並行登録を保存し、同一 Project への CLI cd は選択 Session を保持する。全47コマンドの互換性は引き続き対応表で個別に管理する。

### Catalog と追加の Session 操作

GET `/api/v1/cli/catalog` は固定の RootCommand 登録47項目から宣言された options・子コマンド・alias・列挙値を読み出す。コマンド実行、任意クラス選択、Backend の任意ファイル一覧は提供しない。CLI は通常の refresh で取得し、help と Tab は immutable cache を使う。100回の補完でも追加 HTTP がゼロであることを確認した。動的候補とローカルパス補完は追加回帰で検証した。CLI 起動時はローカル cwd を初期 Project に選ぶ。

config/skill/subagent、reflection lesson の専用 API と既存整形を追加。Dependency の check は WEB の Tool Permission を通してから観測する。memory summarize/consolidate は保存承認を既存 Shell と共有し、Sleep と共に ConversationInputRouter の操作キュー・永続 receipt・Session 排他を通す。terminal event 後も実処理の cleanup が終わるまで排他を保つ。実 HTTP の replay、異なる payload の409、チャットとの競合、余分な会話 Turn がないことを検証した。旧メモリ要約・統合・export の対象範囲は既存 Shell 同様の全体。

追加メモリ関連25件が成功。最新 CLI は57件、launcher は実 ConPTY を含む5件が成功。配布依存は10 jars、合計7,742,025 bytes（CLI の純粋引数パーサーに picocli を含む）。Spring、SQLite、provider SDK は CLI に含まれない。最新 Backend `rei-backend-memory-parity.jar` の GUI 強制 close E2E は PASS：専用両ペイン終了後の同一 Backend、RUNNING → COMPLETED、別 CLI 再接続、認証 stop を確認。
通常の2ペイン E2E も同じ最新版 jar で PASS：第一ペイン終了後も第二 CLI と RUNNING の Run が生存し、第二 CLI が SSE 完了を受信して追加 Session を作成した。
# 固定ドメイン操作の互換性検証（2026-10-11）

Google Calendar / Tasks、Storage retention・maintenance、Activity の全宣言操作、Bluesky reply/history、interest discover、Feed の OPML・更新・要約・記事操作を固定 API へ接続した。表示は新しい command instance の専用 writer で既存 Shell 実装を共有する。Web の既存 JSON 契約は維持し、CLI 用 representation は明示 header で切り替える。OAuth と LLM を伴う操作は既存 Run キュー・権限・Session 所有権を使い、受理後に副作用を自動再送しない。

LLM capture は CLI ごとのランダム UUID と Session に予約を紐付け、Chat の dispatch 前に消費する。原本は既存の上限付き JVM メモリにのみ保持する。raw/export は既存の明示確認を維持し、export は既存 CREATE_NEW・親 traversal・symlink 検証を共有する。別 CLI の予約消費、表示の masking、原本非上書きをテストした。

VACUUM は turns だけでなく active conversation_admissions も検査する。会話 Turn を作らない操作と、terminal event 後の cleanup 中にも Session lease が残っていれば拒否する実 SQLite テストを追加した。

対象テストは Activity 43 件、Google/Vector 53 件、Storage 26 件、Social 28 件、Capture/Chat 28 件、Feed/Capture 30 件が成功（対象間の重複あり）。CLI reactor は成功し、実 ConPTY の対話 Shell・JLine 復帰も含む。

GUI close E2E: `target/windows-terminal-e2e-76b53f8c-7fff-44f8-aa1f-2bf9991fd97c/result.json`、Backend instance `64172cf2-0607-4d40-84c6-01eb46800b25`。専用 UUID の HWND に WindowPattern.Close を実行し、両 pane host 終了、Run RUNNING 継続→COMPLETED、CLI 再接続・history 取得・新 Session・認証 stop・endpoint 除去を確認した。Windows Terminal が複数ウィンドウを同じ PID でホストするため、MainWindowHandle ではなく UI Automation の top-level HWND を照合する。

search の回答 streaming、image の全 options、summarize の直近表示、動的候補・パス補完・help・引数検証を追加した。voice はユーザー指定で対象外。以下に今回の最終検証を記録する。


## 最終検証記録（2026-10-11、今回の依頼）

Phase 1 の ownership、migration 前の二重起動拒否、identity/protocol 検証、receipt の同時受付・restart/replay・changed payload・expiry、Session admission と runner cleanup の排他を検証した。実 Windows Backend の起動・再接続・停止では `windows-cli-e2e-1b38ef4d-05e6-4e71-8c20-20c4f4dac37f` が PASS。起動 cwd と選択 Project の二つが登録され、全47コマンドの cached help を実 CLI で表示した。

Shell 登録47項目を保持し、音声を除く46コマンドを対応した。Picocli の option 位置、alias、排他 group、repeat/CSV、空 quoted 引数、裸の container、子コマンド help を回帰検証した。Root 宣言と純粋 catalog の usage を比較し、実行側の tokenizer・研究系 request parser・既存 formatter を共有する。宣言済み `--help` は必須引数省略・途中の option 位置にも対応し、`--` 後の literal は help として扱わない。CLI の Tab は HTTP を呼ばず、Project/Session の cache とローカルのパスを使う。

今回追加した主な固定 API は `/api/v1/search/answers`、`/api/v1/images/cli`、`/api/v1/summaries/cli`、`/api/v1/projects/{id}/summaries/latest`、`/api/v1/vector-documents/ingestions`、`/api/v1/google-operations/calendar/list|add`、`/api/v1/google-operations/tasks/list|add` と ID の complete/delete、`/api/v1/reminders/commands`、`/api/v1/briefing/commands/today`、`/api/v1/projects/{id}/work-context/updates` と report/history/report、checkpoint report。HTTP に任意の command text・reflection target・service selector を渡す API は作らない。Google の暗黙 OAuth も受理した Run の SSE に URL を出し、HTTP request を認可待ちで保持しない。

TDD では image failure/cancel が completed 履歴へ残る問題、option 位置と repeated evidence の正規化、追加 metadata と旧 Shell usage の差を red で再現してから修正した。既存 Web の Dependency read 契約はそのままに、CLI representation だけ facts を flush する。HTTP integration のテスト構成へ新しい権限ガードと承認 repository を補った。

最終 Backend 全回帰は `-Pfull test`（`target/recovery-parity`）で4,792件、成功4,790・失敗0・エラー0・skip 2、BUILD SUCCESS。skip は既存の opt-in I/O benchmark と実 PlantUML jar 未設定のケースであり、今回のテストを無効化していない。ログは `target/final-full-regression-green.log`。

最新 CLI reactor は CLI 76 件・launcher 5 件、計81件成功、skip 0。`WindowsPtyTest` は実 ConPTY と native JLine で `/sh` の選択 cwd、stdin、Shell 終了後の入力復帰、Ctrl+C 後の help/exit を検証した。Native は UI 99 件（25 files）、Rust 120 件成功、typecheck 成功。CLI 配布依存は10 jars、7,783,021 bytes。Spring、SQLite、provider SDK を含まない。

Windows Terminal の通常2ペイン E2E は `windows-terminal-e2e-35133712-e50e-418f-9667-3d4c1659a31f` が PASS。第一 CLI の exit 後も同一 Backend と第二 CLI が生存し、Run は RUNNING を維持、第二 CLI が SSE の COMPLETED を受信して追加 Session を作成した。

GUI close E2E は `windows-terminal-e2e-e4deeabf-3b77-479b-8c37-83648c58d6d9` が PASS。専用 UUID の HWND を UI Automation WindowPattern.Close で閉じた後、両 pane host の終了、Backend instance `82e1af28-c13e-4089-b74a-7f185654c483` の継続、Run `9564be1b-7f03-4ae4-8254-76c5841e018b` の RUNNING → COMPLETED、新 CLI の Run/history 取得・新 Session、認証 stop と endpoint 除去を確認した。Terminal のペインでは stdin/stdout を fixture に接続し、対話キー入力は上記の実 ConPTY で別途検証する。provider はローカル Python fixture で、外部 LLM の実行とは区別する。

ユーザー指定で voice は延期した。実 Google OAuth、Bluesky、外部 codex/claude、live LLM の実アカウントでの成功は今回の fixture/regression 結果に含めない。macOS service 起動、receipt tombstone・終了済み CLI history の自動掃除は実装範囲外。既存 Shell と旧起動スクリプトは維持し、同じブランチで作業し、worktree と PR は作成しない。


### 補完表示の追加修正（2026-10-11）

軽量 CLI の JLine Candidate に、既存 Shell と同じ `command` group と Root catalog の説明文を渡す。子コマンド・alias にも説明を付け、option/value とローカルパスは既存の kind で表示する。Windows の CASE_INSENSITIVE と BRACKETED_PASTE 設定も既存 Shell とそろえた。CLI 固有の追加コマンドは維持する。

表示テストは旧 Candidate の group が null であることを red で再現し、修正後は説明・alias・reader 設定・Tab 中 HTTP 増加なしを検証した。実 ConPTY でも `/` + Tab の `command` 見出しと説明を確認し、その後の `/sh`・入力・cwd・JLine 復帰・Ctrl+C 後 help/exit が成功。CLI 76 件・launcher 5 件、計81件成功、失敗・エラー・skip 0。ログは `target/completion-display-pty-regression.log`。Backend と既存 Shell の実装は変更していない。
