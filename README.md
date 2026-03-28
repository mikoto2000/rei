# Rei

Rei は、ローカルで動かす AI 秘書シェルです。OpenAI 互換 API を使った対話に加え、Google Calendar、タスク管理、日次ブリーフィング、リマインド、文書埋め込みを扱えます。

## 主な機能

- OpenAI 互換 API を使った対話
- `model` / `models` による chat モデルの確認・切り替え
- Google Calendar の認可、予定一覧、予定追加
- タスクの追加、一覧、完了、削除
- その日の予定・未完了タスク・関連文書をまとめる日次ブリーフィング
- 指定日時または予定の何分前かでのリマインド
- 文書をベクトルストアへ埋め込んだうえでの RAG
- Brave Search API を使った Web 検索ツール

## セットアップ

### OpenAI Compatible API

最低限必要な設定:

```bash
export REI_OPENAI_BASE_URL=https://api.openai.com
export REI_OPENAI_API_KEY=your-api-key
export REI_OPENAI_CHAT_MODEL=gpt-5.4
export REI_OPENAI_EMBEDDING_MODEL=text-embedding-3-small
```

`REI_OPENAI_BASE_URL` には OpenAI 互換 API のベース URL を設定してください。
サーバーによっては `https://host/v1` まで含める構成が必要です。

### Google Calendar

Google Calendar 連携を有効にする場合は、Google Cloud で Desktop app の OAuth クライアントを作成し、資格情報 JSON を `REI_GOOGLE_CALENDAR_CREDENTIALS_PATH` に配置してください。

```bash
export REI_GOOGLE_CALENDAR_ENABLED=true
export REI_GOOGLE_CALENDAR_CREDENTIALS_PATH=$HOME/.config/rei/google-calendar-credentials.json
export REI_GOOGLE_CALENDAR_TIME_ZONE=Asia/Tokyo
```

### Web Search

Web 検索を有効にする場合は Brave Search API を設定してください。

```bash
export REI_WEB_SEARCH_ENABLED=true
export REI_WEB_SEARCH_API_KEY=your-brave-search-api-key
```

必要に応じて次も指定できます。

```bash
export REI_WEB_SEARCH_BASE_URL=https://api.search.brave.com/res/v1/web/search
export REI_WEB_SEARCH_TIMEOUT_SECONDS=10
export REI_WEB_SEARCH_MAX_RESULTS=5
```

## 使い方

起動:

```bash
./mvnw spring-boot:run
```

対話:

```text
こんにちは
今日の予定を教えて
明日の朝に確認したいタスクを追加して
```

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

```text
/embed ./docs/spec.md ./docs/meeting-note.pdf
```

読み込んだ文書はベクトルストアに保存され、対話時の RAG に使われます。

## AI ツール

チャット中の AI は、内部的に次のツール群を利用できます。

- ファイル操作、日付取得、外部コマンド実行
- Google Calendar の予定一覧・予定作成
- タスク作成・更新・完了・削除
- 日次ブリーフィング生成
- リマインド作成・一覧
- Web 検索

## テスト

```bash
./mvnw test -q
```

## License

このソフトウェアは MIT ライセンスの下で提供されます。
詳細については [LICENSE](./LICENSE) ファイルを参照してください。

## Author

mikoto2000 <mikoto2000@gmail.com>
