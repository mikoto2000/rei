# Rei

Rei は、ターミナルで使う AI 秘書シェルです。OpenAI 互換 API を使った対話を中心に、調査、文書検索、予定・タスク管理などを一つの CLI で行えます。

アプリは手元の PC で動作し、AI の処理には設定した API サーバーを利用します。ローカルのモデルサーバーにも接続できます。

## できること

- AI との対話、画像を添付した質問、画像生成
- [論文検索・日本語要約・翻訳・Paper Library](docs/paper-research.md)（`/paper`）
- Web 検索、URL の要約、登録した文書を参照した回答
- プロジェクトごとの会話管理、履歴の検索、複数プロジェクトでの同時実行
- 永続 Goal と工程の管理、保存済みチェックポイントからの再開、条件や時刻を指定した継続
- Google Calendar の予定操作、Google Tasks のタスク管理
- RSS/Atom の購読、日次ブリーフィング、リマインド
- MCP による外部ツール連携、SubAgent への作業の委譲、Windows の画面操作

## はじめに

### 1. 必要なものを用意する

- JDK 25 以上（`java -version` で確認できます）
- このリポジトリのソース一式
- 利用する OpenAI 互換 API の接続先、モデル名、必要に応じた API キー

以下はソースから起動する手順です。リポジトリのルートで実行してください。Maven は同梱の Maven Wrapper を使用できます。初回は依存ファイルのダウンロードにネットワーク接続が必要です。

Backend JAR と軽量 CLI をまとめてビルドする場合は、次のスクリプトを実行します。既定では両方のテスト実行を省略します。テストも実行する場合は `-DskipTests=false` を渡します。Maven の追加引数は両方のビルドに渡ります。更新先の JAR を使用中のプロセスは、ビルド前に通常終了してください。

```powershell
.\build.bat
# テストも実行する場合
.\build.bat -DskipTests=false
```

```sh
sh ./build.sh
# テストも実行する場合
sh ./build.sh -DskipTests=false
```

### 2. AI の接続先を設定する

次の例は、同じ PC の `http://localhost:11434` で公開されている `qwen3.5:9b` に接続します。URL・モデル名・キーを実際の接続先に置き換えてください。キーが不要なローカルサーバーでは `dummy-key` を指定できます。

Windows（PowerShell）

```powershell
$env:REI_OPENAI_BASE_URL = 'http://localhost:11434'
$env:REI_OPENAI_API_KEY = 'dummy-key'
$env:REI_OPENAI_CHAT_MODEL = 'qwen3.5:9b'
$env:REI_EMBEDDING_ENABLED = 'false'
```

Linux / macOS（Bash）

```bash
export REI_OPENAI_BASE_URL=http://localhost:11434
export REI_OPENAI_API_KEY=dummy-key
export REI_OPENAI_CHAT_MODEL=qwen3.5:9b
export REI_EMBEDDING_ENABLED=false
```

この例では、まず対話を始められるよう文書の埋め込みを無効にしています。文書検索を使うときは `REI_OPENAI_EMBEDDING_MODEL` を設定し、`REI_EMBEDDING_ENABLED=true` にして再起動してください。画像生成には画像生成 API に対応する接続先とモデルが必要です。

これらの環境変数は、設定したターミナルから起動する Rei に適用されます。毎回入力したくない場合は、起動後に[設定ファイル](#設定とデータの保存先)を作成してください。

### 3. 起動して話しかける

従来 Shell をソースから起動するには、次のコマンドを使います。ビルド済み JAR からは Windows で `start.bat`、Linux / macOS で `sh ./start.sh` を使えます。

Windows（PowerShell）

```powershell
.\mvnw.cmd spring-boot:run
```

Linux / macOS

```bash
./mvnw spring-boot:run
```

Rei の入力プロンプトが表示されたら、文章をそのまま入力します。

```text
こんにちは
今日取り組むことを一緒に整理して
```

操作コマンドは `/` で始めます。`/help` でヘルプを表示し、`/exit` で終了できます。

### 軽量 CLI と Native Client

| クライアント | 起動・接続 |
| --- | --- |
| 軽量 CLI | ビルド後、`REI_API_KEY` を設定して Windows では `.\rei.bat`、Linux では `sh ./rei.sh` を実行します。既定の `auto` は同じデータディレクトリの Backend に接続するか、Backend を別プロセスで起動します。`--mode=client` は接続のみ、`--mode=server` は Backend の起動、`--mode=legacy-shell` は従来 Shell の起動です。macOS では `sh ./rei.sh --mode=legacy-shell` で従来 Shell を起動できます。[起動・停止と OS ごとの制約](docs/lightweight-cli-client.md) |
| Native Client | Tauri 2 のクライアントを起動し、`REI_API_KEY` を設定した Rei の Web API に接続します。Web API Phase 1〜4 に対応し、会話履歴・Run・SSE の再接続・Workspace 操作を提供します。[起動・ビルド](client/README.md)・[API と対応機能](docs/native-web-api.md) |

`REI_API_KEY` は Rei のクライアントと Backend の間の認証に使います。モデル接続用のキーは `REI_OPENAI_API_KEY` に設定します。

音声入力と `/chatgpt` の認証・利用枠操作には従来 Shell を使います。[コマンド対応表](docs/lightweight-cli-command-matrix.md)

起動時の作業ディレクトリは、軽量 CLI では `.\rei.bat --project F:\project\rei` や `sh ./rei.sh --project .`、従来 Shell では `./mvnw spring-boot:run '-Dspring-boot.run.arguments=--project ../other-project'`（Windows では `.\mvnw.cmd`）のように指定します。相対パスの基準は起動時のカレントディレクトリです。指定先には既存のディレクトリを使ってください。

## 基本操作

以下はすべて Rei の入力プロンプトに入力するコマンドです。

| やりたいこと | 入力例 |
| --- | --- |
| 現在のモデルを確認する | `/model` |
| 利用できるモデルを確認する | `/models` |
| モデルを変更する | `/model <モデル名>` |
| 調べる | `/search 調べたいこと` |
| Web ページを要約する | `/summarize https://example.com/article` |
| 最後の要約を再表示する | `/summarize` |
| 画像を生成する | `/image "夕暮れの港の水彩画"` |
| 会話履歴を表示する | `/history` |
| 現在の Session を確認する | `/session` または `/session show` |
| Session 一覧を見る | `/session list`（現在の Project、更新日時降順） |
| 新しい会話を開始する | `/session new [title]`（即時作成・選択） |
| 既知 Session を再開する | `/session switch <sessionId>`（`resume` も利用可） |
| 会話履歴を検索する | `/history search 検索したい言葉` |
| 現在のプロジェクトの再開候補を再表示する | `/resume overview` |
| 実行中の処理を確認する | `/runs` |
| 選択中プロジェクトの実行に停止を要求する | `/cancel` |
| 特定の Run に停止を要求する | `/cancel RUN_ID` |
| テキスト Run の時間計測を見る | `/timing last --details` または `/timing RUN_ID --details` |
| 現在の設定と環境を診断する | `/doctor` |

`/search` は Web と登録済み文書を検索して回答します。OpenAI 検索プロバイダの追加設定は [Web 検索設定](docs/web-search-optimization.md#openai-responses-api-を検索プロバイダとして追加する) を参照してください。文書の埋め込みが無効な場合は Web 検索だけを使います。`/models` は接続先がモデル一覧の取得に対応している場合に利用できます。

### 作業中の追加入力とプロジェクト切り替え

AI の実行中も入力できます。同じプロジェクトへの追加入力は順番に受け付けられ、処理の区切りで反映されます。

作業フォルダーを切り替えるには、次のように入力します。パスに空白がある場合は引用符で囲んでください。

```text
/project cd "C:\work\my-project"
/project list
```

`/project cd` は未登録のフォルダーも登録し、そのプロジェクトで最後に更新されたセッションに切り替えます。セッションがなければ次の入力で新規作成します。Linux / macOS では `/home/your-name/work/my-project` などのパスを指定します。別のプロジェクトに切り替えると、先の処理を続けたまま新しい依頼を開始できます。

プロンプトの `[rei] [2 running]` は選択中のプロジェクト名と、全プロジェクトの実行件数を表します。URL 要約と画像生成もバックグラウンドで動き、件数に含まれます。`/runs` で進行状況を確認でき、終了時には通知が出ます。

タスク実行中は、環境変数 `REI_DEBUG` が設定されている場合だけ、この行の直上に全体進捗を表示します。値の内容は問いません。軽量CLIではCLIプロセス自身の環境変数を参照します。各 Run の目的、記録された完了工程（✓）・残り工程（○）、完了数を工程一覧として表示し、更新時は入力中の文字とカーソル位置を維持します。工程が未登録の間は「工程を確認中」と表示し、Run が終了すると入力欄から取り除きます。完了数は `updateTaskState` に記録された工程に基づく目安で、処理時間の割合や実行回数ではありません。軽量CLIも同じ表示を約1秒間隔で更新し、切断時には古い進捗を消去します。

`/cancel` は選択中の Project で停止対象に登録された実行に、`/cancel RUN_ID` は指定した Run に停止を要求します。`/runs` に `CANCEL_REQUESTED` が出た場合は終了通知を待ってください。停止要求から 30 秒以上経過しても処理が残っている場合は `CANCEL_STALLED`、停止処理で失敗した場合は `CANCEL_FAILED` が表示されます。軽量 CLI の Ctrl+C は表示の購読を解除します。[停止状態と対象範囲](docs/run-cancellation-lifecycle.md)

再起動後に保存済みの作業を継続する場合は、チェックポイントを確認して明示的に Resume します。[チェックポイントと Resume](docs/persistent-checkpoint-resume.md)

### 会話履歴を検索する

`/history` は永続 Session 一覧を更新日時順に表示し、`/history show <sessionId>` で詳細と会話を確認できます。再起動後も利用でき、一覧・Turn とも cursor pagination に対応します。従来の選択中プロジェクトの直近50メッセージは `/history show` で表示します。検索は現在のプロジェクトを優先し、結果が不十分なら他のプロジェクトも検索します。現在のプロジェクトだけに絞るには `/history search --current "検索語"` を使ってください。

Web では認証付き `GET /api/v1/sessions`、`GET /api/v1/sessions/{sessionId}`、`GET /api/v1/sessions/{sessionId}/turns` を利用できます。[仕様・Shell 操作・既存データの扱い](docs/session-history.md)を参照してください。

Shell と Native Client は同じ Backend の Session History を利用します。Session は作成時の Project に属し、Shell では同じ Project の Session を選択できます。従来 Shell の起動直後は Session 未選択です。Session 台帳に登録されていない旧 Shell 履歴は、従来の履歴として保存されます。[Shell Session の操作](docs/shell-session-commands.md)

## 機能別ガイド

プロジェクトの目的・決定・進捗・未解決点・次のアクションを会話間で引き継ぐ [Work Context](docs/project-work-context.md) は、`/work`・`/work update`・`/work history` と自然言語から利用できます。自動更新は初期状態では無効です。

必要な機能の設定を済ませてから利用してください。日付・ファイルパス・ID は手元の値に置き換えます。

| 機能 | 操作・設定 |
| --- | --- |
| 画像の添付 | [ファイル・クリップボードから入力](docs/usage.md#画像の入力)（画像対応モデルが必要） |
| 画像生成 | [生成・保存先の指定](docs/usage.md#画像生成) |
| 別会話の並行実行 | [会話・読み取り・作業Runの使い分け](docs/concurrent-conversation-runs.md) |
| Agent Task Manager | [Project横断の状態一覧と明示操作](docs/task-manager.md) |
| Goal・工程・継続 | [Goal と工程の管理](docs/goal-stage-implementation.md#api--cli)・[タイマーの登録と操作](docs/persistent-agent-scheduler.md) |
| 生成物の受取 | [Artifact一覧・preview・Downloads保存](docs/artifact-delivery.md) |
| Google Calendar | [認証設定](docs/configuration.md#google-calendar-と-google-tasks) → [予定の一覧・追加](docs/usage.md#google-calendar) |
| Google Tasks | [認証設定](docs/configuration.md#google-calendar-と-google-tasks) → [タスクの追加・完了・削除](docs/usage.md#タスク管理) |
| 日本の祝日・休日 | [内閣府 CSV の配置・対応年設定・更新](docs/japanese-holidays.md) |
| RSS/Atom | [購読・OPML 取り込み・記事要約](docs/usage.md#rss-feed) / [認証付きフィード](docs/private-feeds.md) |
| 日次ブリーフィング | [予定・タスク・新着記事の確認](docs/usage.md#日次ブリーフィング) |
| リマインド | [日時指定・一覧・削除](docs/usage.md#リマインド) |
| 文書検索 | [文書の登録・検索・削除](docs/usage.md#文書の埋め込み) |
| 記憶・会話履歴 | [記憶の管理](docs/usage.md#記憶管理)・[履歴の表示と検索](docs/usage.md#会話履歴)・[手動 Sleep と Auto Sleep](docs/memory-lifecycle-guide.md) |
| Web 検索 | [検索の使い方](docs/usage.md#検索)・[検索サービスの設定](docs/configuration.md#web-検索) |
| URL 要約 | [要約の実行・再表示](docs/usage.md#url-の要約) |
| 通知 | [興味に応じた情報の定期通知](docs/configuration.md#興味に応じた通知) |
| Bluesky | [投稿・自動返信の設定](docs/configuration.md#bluesky-投稿自動返信) |
| MCP | [外部 MCP ツールへの接続](docs/configuration.md#mcp)・[本人専用 MCP サーバーの設定と 6 ツール](docs/mcp-server.md)（サーバーは既定で無効） |
| SubAgent | [作業を委譲する設定と使い方](docs/subagents.md) |
| 外部レビュー | `/agent codex review [target]` または明示的な自然言語依頼で読み取り専用レビューを実行します。Claude Code CLI を使う場合は明示的に有効化して `/agent claude review [target]` を使います。[Codex・Claude の設定と権限](docs/external-agent-delegation.md)・[Claude Code の利用準備](docs/claude-code-reviews.md) |
| 教材レビュー | `/material-review beginner --root training --entry intro.md` で学習順に沿う静的レビュー、`/agent codex material-review [target]` で技術教材の読み取り専用レビューを実行します。[初学者向け教材](docs/beginner-material-review.md)・[技術教材](docs/material-review.md) |
| Windows の画面操作 | [Computer Use の有効化と設定](docs/computer-use.md) |

本人専用 MCP サーバーの詳細は [安全境界](docs/mcp-server-security.md)、[運用](docs/mcp-server-operations.md)、[検証](docs/mcp-server-testing.md)を参照してください。

## 設定とデータの保存先

設定や会話履歴などは、作業フォルダーとは別の共通ディレクトリに保存されます。

| OS | 既定の保存先 |
| --- | --- |
| Windows | `%LOCALAPPDATA%\Rei` |
| Linux | `$XDG_DATA_HOME/rei`（未設定時は `~/.local/share/rei`） |
| macOS | `~/Library/Application Support/Rei` |

環境変数 `REI_DATA_DIR` で保存先を変更できます。各ガイドの `<rei-data-dir>` はこのディレクトリを指します。

設定ファイルのパス確認とテンプレート作成:

```text
/config path
/config init
```

作成された `application.yaml` を編集し、Rei を再起動すると反映されます。設定は環境変数、外部設定ファイル、組み込み設定の順に優先されます。同時に作成される `additional-system-prompt.md` には、AI に常に伝えておきたい指示を記述できます。

詳しくは[設定ガイド](docs/configuration.md)を参照してください。

Slack で会話する場合は [Slack Bot ガイド](docs/slack-bot.md)を参照してください。
明示的に有効化した Rei プロセスが Socket Mode 接続を管理します。通常 Shell、`--backend`、`--mode=server` の起動に対応します。

## 困ったときは

`/doctor` は設定とファイルの状態を確認します。接続・推論・外部 CLI・マイク取得まで調べるには、対象を指定して `--check` を使います。Run の遅延やモデルの応答は `/timing last --details` で確認できます。[環境診断](docs/environment-doctor.md)・[時間計測と保持設定](docs/run-timing.md)

| 症状 | 確認すること |
| --- | --- |
| 起動できない | `java -version` が 25 以上か、リポジトリのルートで実行しているかを確認してください。 |
| AI に接続できない、`401 Unauthorized` | 接続先が稼働しているか、`REI_OPENAI_BASE_URL` と `REI_OPENAI_API_KEY` が正しいかを確認してください。 |
| モデルが見つからない | 接続先が提供するモデル名を `REI_OPENAI_CHAT_MODEL` に指定してください。 |
| Google の資格情報ファイルが見つからない | `REI_GOOGLE_CREDENTIALS_PATH` と JSON ファイルの配置先を確認してください。 |
| Google の認可が失効した | `/schedule auth` または `/task auth` で再認可してください。 |
| Web 検索が使えない | `REI_WEB_SEARCH_ENABLED` と検索サービスの設定を確認してください。Brave には API キーが必要です。Web 検索に失敗すると、`/search` は登録済み文書だけで回答を試みます。 |
| MCP ツールが見つからない | `REI_MCP_ENABLED` と `REI_MCP_STDIO_SERVERS_CONFIG` を確認し、設定変更後に再起動してください。 |
| 絵文字で表示が乱れる | 起動時に `--notmux` を指定すると改善する場合があります。Maven Wrapper では `./mvnw spring-boot:run "-Dspring-boot.run.arguments=--notmux"`（Windows では `./mvnw.cmd`）を使用します。 |

## 開発・ライセンス

GitHubからのReview通知、CI失敗trigger、PR merge待ちは [GitHub Event Trigger](docs/github-event-trigger.md) を参照してください。既定は無効です。

明示Project allowlistから読み取り専用で今日の候補を集計するAPIは [Cross-project Today](docs/cross-project-today.md) を参照してください。

デスクトップの観測・作業履歴は [Activity Capture / Timeline](docs/activity-timeline.md)、
全景補助と時系列統合は [Desktop Context Fusion](docs/activity-desktop-context-fusion.md) を参照してください。
初期状態は無効です。有効化後は `/activity today`、`/activity yesterday`、`/activity pause` などで操作できます。

開発やテストの手順は [DEVELOP.md](DEVELOP.md)、仕様・設計は [.kiro](.kiro/README.md) を参照してください。

新規開発者向けの [図解付きプログラム構造ガイド](docs/program-structure.md) では、Shell・Web API・Native Client の関係と、会話処理・状態管理・ソースコードの読み進め方を説明しています。

[MIT ライセンス](LICENSE)で提供しています。作者: mikoto2000 <mikoto2000@gmail.com>

自然言語による Codex 隔離実装と詳細要件・承認・条件別評価は[要件付き隔離実装ガイド](docs/llm-codex-implementation-delegation.md)を参照してください。対象だけの /agent codex implement は要件確認から進みます。

LLM送信本文の明示記録は [LLM Request Capture](docs/llm-request-capture.md) を参照してください。

音声認識の現行モデル、承認付き取得、旧キャッシュと検証範囲は [Whisper large-v3-turbo FP32 移行](docs/voice-input-turbo-fp32.md) を参照してください。

文脈と辞書を参照する音声LLM補正（既定OFF、自動採用、VOICE承認維持）は [音声LLM補正](docs/voice-llm-correction.md) を参照してください。

対話Shell起動時の音声入力は、[自動開始設定](docs/configuration.md#音声入力の自動開始)で明示的に有効化できます。既定はOFFです。

Sign in with ChatGPT の手動 SubAgent は [利用・設計ドキュメント](docs/chatgpt-plan-subagents.md) を参照してください。

承認待ちの強調枠・入力欄付近の件数表示と端末フォールバックは [承認待ちの端末表示](docs/approval-display.md) を参照してください。
