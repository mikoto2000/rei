# Rei

Rei は、ローカルで動かす AI 秘書シェルです。OpenAI 互換 API を使った対話を中心に、Google Calendar、タスク管理、日次ブリーフィング、リマインド、文書埋め込み、Web 検索、MCP ツール連携を 1 つの CLI にまとめています。

日々の確認や調査をターミナル上で完結させたいときに向いています。ローカルファイルや埋め込み済み文書を参照しながら対話でき、必要に応じて外部 API や MCP サーバーのツールも利用できます。

## 主な機能

- OpenAI 互換 API を使った対話
- `model` / `models` による chat モデルの確認・切り替え
- Google Calendar の認可、予定一覧、予定追加
- タスクの追加、一覧、完了、削除
- その日の予定・未完了タスク・関連文書をまとめる日次ブリーフィング
- 指定日時または予定の何分前かでのリマインド
- 文書をベクトルストアへ埋め込んだうえでの RAG
- Web 検索ツール
  - 上位ページの本文取得、クエリ展開、重複 URL 除外、一次情報優先の再ランキングを含む
- JSON 設定で登録した MCP サーバーのツール利用

## セットアップ

動作要件:

- JDK 25 以上
- Maven Wrapper を使う場合は `./mvnw`、ローカル Maven を使う場合は `mvn`

### OpenAI Compatible API

必須:

```bash
export REI_OPENAI_BASE_URL=https://api.openai.com
export REI_OPENAI_API_KEY=your-api-key
export REI_OPENAI_CHAT_MODEL=gpt-5.4
export REI_OPENAI_EMBEDDING_MODEL=text-embedding-3-small
```

`REI_OPENAI_BASE_URL` には OpenAI 互換 API のベース URL を設定してください。
サーバーによっては `https://host/v1` まで含める構成が必要です。

環境変数:

| 変数 | 要否 | デフォルト | 説明 |
| --- | --- | --- | --- |
| `REI_OPENAI_BASE_URL` | 必須 | `http://localhost:11434` | OpenAI 互換 API のベース URL |
| `REI_OPENAI_API_KEY` | 必須 | `dummy-key` | API キー |
| `REI_OPENAI_CHAT_MODEL` | 必須 | `qwen3.5:9b` | chat 用モデル名 |
| `REI_OPENAI_EMBEDDING_MODEL` | 必須 | `qwen3-embedding:8b` | embedding 用モデル名 |

### Google Calendar

Google Calendar 連携を使う場合は、Google Cloud で Desktop app の OAuth クライアントを作成し、資格情報 JSON を `REI_GOOGLE_CALENDAR_CREDENTIALS_PATH` に配置してください。

手順の概要:

1. Google Cloud Console で対象プロジェクトを作成または選択する
2. Google Calendar API を有効にする
3. OAuth 同意画面を設定する
4. `Credentials` から `OAuth client ID` を作成し、`Desktop app` を選ぶ
5. ダウンロードした JSON を `REI_GOOGLE_CALENDAR_CREDENTIALS_PATH` に配置する

```bash
export REI_GOOGLE_CALENDAR_ENABLED=true
export REI_GOOGLE_CALENDAR_CREDENTIALS_PATH=$HOME/.config/rei/google-calendar-credentials.json
export REI_GOOGLE_CALENDAR_TIME_ZONE=Asia/Tokyo  # タイムゾーン
```

Google Calendar の資格情報と OAuth token は、デフォルトではホームディレクトリ配下の `.config/rei` に保存されます。必要に応じて `REI_GOOGLE_CALENDAR_CREDENTIALS_PATH` と `REI_GOOGLE_CALENDAR_TOKENS_DIR` で上書きできます。

主な環境変数:

| 変数 | 要否 | デフォルト | 説明 |
| --- | --- | --- | --- |
| `REI_GOOGLE_CALENDAR_ENABLED` | 任意 | `false` | Google Calendar 連携を有効化 |
| `REI_GOOGLE_CALENDAR_CREDENTIALS_PATH` | 利用時必須 | `${HOME}/.config/rei/google-calendar-credentials.json` | OAuth クライアント資格情報 JSON |
| `REI_GOOGLE_CALENDAR_TOKENS_DIR` | 任意 | `${HOME}/.config/rei/google-calendar-tokens` | OAuth token 保存先 |
| `REI_GOOGLE_CALENDAR_DEFAULT_CALENDAR_ID` | 任意 | `primary` | 既定カレンダー ID |
| `REI_GOOGLE_CALENDAR_TIME_ZONE` | 任意 | 空 | オフセットなし日時の解釈に使うタイムゾーン |

### Web Search

Web 検索は `providers` 配列で設定します。既定では DuckDuckGo のみ有効です。

DuckDuckGo だけ使う場合:

```bash
export REI_WEB_SEARCH_ENABLED=true
export REI_WEB_SEARCH_DUCKDUCKGO_BASE_URL=https://html.duckduckgo.com/html/
```

Brave も使う場合は `application.yaml` か profile 用 YAML で provider を追加してください。

```yaml
rei:
  web-search:
    enabled: true
    providers:
      - name: duckduckgo
        base-url: ${REI_WEB_SEARCH_DUCKDUCKGO_BASE_URL:https://html.duckduckgo.com/html/}
      - name: brave
        base-url: ${REI_WEB_SEARCH_BRAVE_BASE_URL:https://api.search.brave.com/res/v1/web/search}
        api-key: ${REI_WEB_SEARCH_BRAVE_API_KEY:}
```

主な環境変数:

| 変数 | 要否 | デフォルト | 説明 |
| --- | --- | --- | --- |
| `REI_WEB_SEARCH_ENABLED` | 任意 | `false` | Web 検索を有効化 |
| `REI_WEB_SEARCH_TIMEOUT_SECONDS` | 任意 | `10` | HTTP タイムアウト秒数 |
| `REI_WEB_SEARCH_MAX_RESULTS` | 任意 | `5` | 取得する最大件数 |
| `REI_WEB_SEARCH_DUCKDUCKGO_BASE_URL` | 任意 | `https://html.duckduckgo.com/html/` | DuckDuckGo 検索 URL |
| `REI_WEB_SEARCH_BRAVE_BASE_URL` | Brave 利用時任意 | `https://api.search.brave.com/res/v1/web/search` | Brave Search API URL |
| `REI_WEB_SEARCH_BRAVE_API_KEY` | Brave 利用時必須 | 空 | Brave Search API キー |

### MCP

MCP サーバーを有効にする場合は、JSON 設定ファイルを用意してください。

```bash
export REI_MCP_ENABLED=true
export REI_MCP_STDIO_SERVERS_CONFIG=file:$PWD/.rei/mcp-servers.json
```

`REI_MCP_STDIO_SERVERS_CONFIG` には `file:` 付きの URI を指定します。`.rei/mcp-servers.json` は Claude Desktop 互換形式です。

```json
{
  "mcpServers": {
    "filesystem": {
      "command": "npx",
      "args": [
        "-y",
        "@modelcontextprotocol/server-filesystem",
        "/workspaces/rei"
      ]
    }
  }
}
```

登録した MCP ツールは起動時に読み込まれ、通常の AI ツールと同様にチャット中に自動利用されます。設定変更の反映には再起動が必要です。

主な環境変数:

| 変数 | 要否 | デフォルト | 説明 |
| --- | --- | --- | --- |
| `REI_MCP_ENABLED` | 任意 | `false` | MCP client を有効化 |
| `REI_MCP_STDIO_SERVERS_CONFIG` | 利用時必須 | `file:.rei/mcp-servers.json` | MCP サーバー定義ファイル |

## 使い方

### 起動

```bash
./mvnw spring-boot:run  # JDK 25+ が必要
# または
mvn spring-boot:run
```

アプリが生成する履歴ファイルと SQLite のローカルデータは、起動したカレントディレクトリ配下の `.rei` に保存されます。

### 対話

```text
こんにちは
今日の予定を教えて
明日の朝に確認したいタスクを追加して
```

### CLI の例

```text
$ ./mvnw spring-boot:run
...
rei> こんにちは
=== answer ===
こんにちは。今日は何を進めますか？
rei> /model
gpt-oss:120b
```

### コマンド一覧

| コマンド | 主なサブコマンド | 説明 |
| --- | --- | --- |
| `chat` | なし | 通常の対話 |
| `model` | なし | 現在の chat モデルの確認・変更 |
| `models` | なし | OpenAI 互換 API が返すモデル一覧を表示 |
| `search` | なし | Web 検索とベクトル検索をまとめて回答 |
| `schedule` | `auth`, `list`, `add` | Google Calendar 認可と予定操作 |
| `task` | `add`, `list`, `done`, `delete` | タスク管理 |
| `briefing` | `today` | 日次ブリーフィング表示 |
| `reminder` | `add`, `list`, `delete` | リマインド管理 |
| `embed` | `add`, `search`, `list`, `delete` | 文書埋め込みと検索 |

### モデル

現在モデルの確認・変更:

```text
/model
/model gpt-4.1-mini
```

指定可能なモデル一覧の確認:

```text
/models
```

`models` は接続先の OpenAI 互換 API が `/v1/models` を実装している前提です。

### Google Calendar

初回認可:

```text
/schedule auth
```

予定一覧:

```text
/schedule list --date 2026-03-23
/schedule list --from 2026-03-23T00:00:00+09:00 --to 2026-03-23T23:59:59+09:00
```

予定追加:

```text
/schedule add --start 2026-03-23T09:00:00+09:00 --end 2026-03-23T10:00:00+09:00 定例会議
/schedule add --start 2026-03-23T09:00:00 --end 2026-03-23T10:00:00 --location 会議室A 設計レビュー
```

オフセットなし日時は `REI_GOOGLE_CALENDAR_TIME_ZONE` を基準に解釈されます。

### タスク管理

追加:

```text
/task add --due 2026-04-03 --priority 2 --tag sales,document 提案書作成
```

一覧:

```text
/task list
/task list --priority 2
/task list --tag sales
/task list --due-before 2026-04-03
```

完了・削除:

```text
/task done 1
/task delete 1
```

### 日次ブリーフィング

```text
/briefing today
```

その日の予定、未完了タスク、関連文書、注意点、次アクションをまとめて表示します。

### リマインド

指定日時で追加:

```text
/reminder add --at 2026-03-27T09:00:00+09:00 顧客に返信する
```

基準日時の何分前かで追加:

```text
/reminder add --target 2026-03-27T14:00:00+09:00 --minutes-before 15 今日の 14:00 からの会議
```

一覧・削除:

```text
/reminder list
/reminder delete 1
```

通知は現状、標準出力へ出ます。

### 文書の埋め込み

追加:

```text
/embed ./docs/spec.md ./docs/meeting-note.pdf
/embed add ./docs/spec.md ./docs/meeting-note.pdf
/embed add "./docs/*"
/embed add "./docs/**/*.md"
```

`embed add` は非同期です。コマンド実行後にプロンプトがすぐ返り、読み込み完了または失敗は標準出力に通知されます。`*`, `?`, `[]` を含む引数は Java 側で glob 展開します。シェルで展開したくない場合は `"./docs/*"` のようにクォートしてください。一致するファイルが 0 件ならエラーになります。

検索:

```text
/embed search spring ai
/embed search --top-k 5 --source /absolute/path/to/spec.md spring ai
```

一覧・削除:

```text
/embed list
/embed delete --doc-id <docId>
/embed delete --source /absolute/path/to/spec.md
```

読み込んだ文書はベクトルストアに保存され、対話時の RAG に使われます。

現状のベクトルストアは、起動したカレントディレクトリ配下の `.rei/vectorstore.db` に保存されます。アプリ本体の履歴やタスクなどで使う `.rei/memory.db` とは別ファイルです。

検索には `sqlite-vec` を使います。埋め込みは `vec0` 仮想テーブルに保持し、KNN 検索に lexical prefilter と軽い rerank を組み合わせています。`source` / `docId` の絞り込みも検索時に適用されます。

登録時は `docId` / `source` / `chunkIndex` を必須 metadata として扱い、欠損している文書はエラーにします。検索時に embedding 次元が一致しない場合もエラーにします。`replaceBySource` は source 単位の delete + insert を 1 トランザクションで実行し、途中失敗時はロールバックされます。文書一覧や削除も `document_chunks_vec` の集約で処理します。

`similarityThresholdAll()` を使っても score が 0 以下の結果は返しません。現在の実装では「関連性がない候補を除外する」挙動を優先しています。SQLite ファイル破損時は破損として、ロック発生時はロックとして明示的に失敗させます。存在しない `docId` / `source` の削除は 0 件または `false` を返します。

### 検索

```text
/search spring ai latest
/search --source /absolute/path/to/spec.md spring ai tools
```

`/search` はベクトル検索結果と Web 検索結果をまとめて回答します。Web 側は検索結果の snippet をそのまま使うのではなく、上位ページの本文を取得したうえで、クエリ展開、重複 URL 除外、一次情報優先の再ランキングを行います。

Web 検索が無効、API キー未設定、不正 API キーなどで失敗した場合は、Web 検索をスキップしてベクトルストアの内容だけで回答します。その場合は出力に `[web search skipped] ...` が表示されます。

## AI ツール

チャット中の AI は、内部的に次のツール群を利用できます。

- ファイル操作、日付取得、外部コマンド実行
- Google Calendar の予定一覧・予定作成
- タスク作成・更新・完了・削除
- 日次ブリーフィング生成
- リマインド作成・一覧
- Web 検索
- MCP サーバー経由のツール

## テスト

```bash
./mvnw test -q  # -q は簡易出力
```

主にユニットテストと Spring コンポーネントの結合テストを含みます。

## よくあるエラー

- `REI_GOOGLE_CALENDAR_CREDENTIALS_PATH ... not found`
  - 資格情報 JSON の配置先とパスを確認してください。
- `No ToolCallback found for tool name: ...`
  - モデルが存在しない tool 名を生成しているか、MCP 設定が読み込まれていません。`REI_MCP_ENABLED` と `REI_MCP_STDIO_SERVERS_CONFIG` を確認してください。
- `401 Unauthorized`
  - `REI_OPENAI_API_KEY` と `REI_OPENAI_BASE_URL` の組み合わせを確認してください。
- Web 検索結果が空
  - `REI_WEB_SEARCH_ENABLED=true` と `rei.web-search.providers` の設定を確認してください。Brave を使う場合は `api-key` も必要です。
- `Web search is disabled. Set REI_WEB_SEARCH_ENABLED=true to enable it.`
  - Web 検索を使う場合は `REI_WEB_SEARCH_ENABLED=true` を設定してください。設定しなくても `/search` はベクトルストアのみで続行します。
- `Web search API key is not configured for provider brave.`
  - Brave provider を使う場合は `rei.web-search.providers[].api-key` を設定してください。未設定でも `/search` はベクトルストアのみで続行します。
- 絵文字でバッファが乱れる
  - `--notmux` オプションを付けて起動してみてください。改善する場合があります。

## License

このソフトウェアは MIT ライセンスの下で提供されます。
詳細については [LICENSE](./LICENSE) ファイルを参照してください。

## Author

mikoto2000 <mikoto2000@gmail.com>
