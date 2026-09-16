# Rei

Web API 向けの Tauri 2 クライアント（Phase 1 / 2）は [client/README.md](client/README.md) を参照してください。Windows での開発・ビルド、Conversation / Run UI、SSE 再接続、モバイルの検証状況を記載しています。

Codex による read-only 外部レビューは `/agent codex review [target]` または明示的な自然言語依頼で利用できます。
必要な CLI capability、設定、安全境界は [External Agent Delegation](docs/external-agent-delegation.md) を参照してください。

Rei は、ターミナルで使う AI 秘書シェルです。OpenAI 互換 API を使った対話を中心に、調査、文書検索、予定・タスク管理などを一つの CLI で行えます。

アプリは手元の PC で動作し、AI の処理には設定した API サーバーを利用します。ローカルのモデルサーバーにも接続できます。

## できること

- AI との対話、画像を添付した質問、画像生成
- Web 検索、URL の要約、登録した文書を参照した回答
- プロジェクトごとの会話管理、履歴の検索、複数プロジェクトでの同時実行
- Google Calendar の予定操作、Google Tasks のタスク管理
- RSS/Atom の購読、日次ブリーフィング、リマインド
- MCP による外部ツール連携、SubAgent への作業の委譲、Windows の画面操作

## はじめに

### 1. 必要なものを用意する

- JDK 25 以上（`java -version` で確認できます）
- このリポジトリのソース一式
- 利用する OpenAI 互換 API の接続先、モデル名、必要に応じた API キー

以下はソースから起動する手順です。リポジトリのルートで実行してください。Maven は同梱の Maven Wrapper を使用できます。初回は依存ファイルのダウンロードにネットワーク接続が必要です。

### 2. AI の接続先を設定する

次の例は、同じ PC の `http://localhost:11434` で公開されている `qwen3.5:9b` に接続します。URL・モデル名・キーを実際の接続先に置き換えてください。キーが不要なローカルサーバーでは `dummy-key` を指定できます。

Windows（PowerShell）:

```powershell
$env:REI_OPENAI_BASE_URL = 'http://localhost:11434'
$env:REI_OPENAI_API_KEY = 'dummy-key'
$env:REI_OPENAI_CHAT_MODEL = 'qwen3.5:9b'
$env:REI_EMBEDDING_ENABLED = 'false'
```

Linux / macOS（Bash）:

```bash
export REI_OPENAI_BASE_URL=http://localhost:11434
export REI_OPENAI_API_KEY=dummy-key
export REI_OPENAI_CHAT_MODEL=qwen3.5:9b
export REI_EMBEDDING_ENABLED=false
```

この例では、まず対話を始められるよう文書の埋め込みを無効にしています。文書検索を使うときは `REI_OPENAI_EMBEDDING_MODEL` を設定し、`REI_EMBEDDING_ENABLED=true` にして再起動してください。画像生成には画像生成 API に対応する接続先とモデルが必要です。

これらの環境変数は、設定したターミナルから起動する Rei に適用されます。毎回入力したくない場合は、起動後に[設定ファイル](#設定とデータの保存先)を作成してください。

### 3. 起動して話しかける

Windows（PowerShell）:

```powershell
.\mvnw.cmd spring-boot:run
```

Linux / macOS:

```bash
./mvnw spring-boot:run
```

Rei の入力プロンプトが表示されたら、文章をそのまま入力します。

```text
こんにちは
今日取り組むことを一緒に整理して
```

操作コマンドは `/` で始めます。`/help` でヘルプを表示し、`/exit` で終了できます。

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
| 会話履歴を検索する | `/history search 検索したい言葉` |
| 実行中の処理を確認する | `/runs` |
| 選択中プロジェクトの AI の実行を中止する | `/cancel` |

`/search` は Web と登録済み文書を検索して回答します。文書の埋め込みが無効な場合は Web 検索だけを使います。`/models` は接続先がモデル一覧の取得に対応している場合に利用できます。

### 作業中の追加入力とプロジェクト切り替え

AI の実行中も入力できます。同じプロジェクトへの追加入力は順番に受け付けられ、処理の区切りで反映されます。

作業フォルダーを切り替えるには、次のように入力します。パスに空白がある場合は引用符で囲んでください。

```text
/project cd "C:\work\my-project"
/project list
```

`/project cd` は未登録のフォルダーも登録し、そのプロジェクトの会話に切り替えます。Linux / macOS では `/home/your-name/work/my-project` などのパスを指定します。別のプロジェクトに切り替えると、先の処理を続けたまま新しい依頼を開始できます。

プロンプトの `[rei] [2 running]` は選択中のプロジェクト名と、全プロジェクトの実行件数を表します。URL 要約と画像生成もバックグラウンドで動き、件数に含まれます。`/runs` で進行状況を確認でき、終了時には通知が出ます。

`/cancel` が中止するのは選択中プロジェクトの AI の実行です。URL 要約や画像生成は対象外です。実行中の処理はアプリを再起動しても再開されません。

### 会話履歴を検索する

`/history` は永続 Session 一覧を更新日時順に表示し、`/history show <sessionId>` で詳細と会話を確認できます。再起動後も利用でき、一覧・Turn とも cursor pagination に対応します。従来の選択中プロジェクトの直近50メッセージは `/history show` で表示します。検索は現在のプロジェクトを優先し、結果が不十分なら他のプロジェクトも検索します。現在のプロジェクトだけに絞るには `/history search --current "検索語"` を使ってください。

Web では認証付き `GET /api/v1/sessions`、`GET /api/v1/sessions/{sessionId}`、`GET /api/v1/sessions/{sessionId}/turns` を利用できます。[仕様・Shell 操作・既存データの扱い](docs/session-history.md)を参照してください。

## 機能別ガイド

必要な機能の設定を済ませてから利用してください。日付・ファイルパス・ID は手元の値に置き換えます。

| 機能 | 操作・設定 |
| --- | --- |
| 画像の添付 | [ファイル・クリップボードから入力](docs/usage.md#画像の入力)（画像対応モデルが必要） |
| 画像生成 | [生成・保存先の指定](docs/usage.md#画像生成) |
| Google Calendar | [認証設定](docs/configuration.md#google-calendar-と-google-tasks) → [予定の一覧・追加](docs/usage.md#google-calendar) |
| Google Tasks | [認証設定](docs/configuration.md#google-calendar-と-google-tasks) → [タスクの追加・完了・削除](docs/usage.md#タスク管理) |
| RSS/Atom | [購読・OPML 取り込み・記事要約](docs/usage.md#rss-feed) |
| 日次ブリーフィング | [予定・タスク・新着記事の確認](docs/usage.md#日次ブリーフィング) |
| リマインド | [日時指定・一覧・削除](docs/usage.md#リマインド) |
| 文書検索 | [文書の登録・検索・削除](docs/usage.md#文書の埋め込み) |
| 記憶・会話履歴 | [記憶の管理](docs/usage.md#記憶管理)・[履歴の表示と検索](docs/usage.md#会話履歴) |
| Web 検索 | [検索の使い方](docs/usage.md#検索)・[検索サービスの設定](docs/configuration.md#web-検索) |
| URL 要約 | [要約の実行・再表示](docs/usage.md#url-の要約) |
| 通知 | [興味に応じた情報の定期通知](docs/configuration.md#興味に応じた通知) |
| Bluesky | [投稿・自動返信の設定](docs/configuration.md#bluesky-投稿自動返信) |
| MCP | [外部ツールの接続設定](docs/configuration.md#mcp) |
| SubAgent | [作業を委譲する設定と使い方](docs/subagents.md) |
| Windows の画面操作 | [Computer Use の有効化と設定](docs/computer-use.md) |

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

## 困ったときは

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

開発やテストの手順は [DEVELOP.md](DEVELOP.md)、仕様・設計は [.kiro](.kiro/README.md) を参照してください。

[MIT ライセンス](LICENSE)で提供しています。作者: mikoto2000 <mikoto2000@gmail.com>
