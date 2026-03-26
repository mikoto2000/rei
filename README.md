# Rei

「玲」は、 AI 秘書になる予定のモノです。

## OpenAI Compatible API

Rei は Spring AI の OpenAI クライアントを使って、OpenAI 互換 API を利用します。

最低限必要な設定:

```bash
export REI_OPENAI_BASE_URL=http://localhost:8000
export REI_OPENAI_API_KEY=dummy
export REI_OPENAI_CHAT_MODEL=gpt-4o-mini
export REI_OPENAI_EMBEDDING_MODEL=text-embedding-3-small
```

`REI_OPENAI_BASE_URL` には OpenAI 互換 API のベース URL を設定してください。
サーバーによっては `http://host:port/v1` まで含める構成が必要です。

現在モデルの確認・変更:

```bash
/model
/model gpt-4.1-mini
```

指定可能なモデル一覧の確認:

```bash
/models
```

## Google Calendar

Google Calendar 連携を有効にする場合は、Google Cloud で Desktop app の OAuth クライアントを作成し、資格情報 JSON を `REI_GOOGLE_CALENDAR_CREDENTIALS_PATH` に配置してください。

有効化:

```bash
export REI_GOOGLE_CALENDAR_ENABLED=true
export REI_GOOGLE_CALENDAR_CREDENTIALS_PATH=$HOME/.config/rei/google-calendar-credentials.json
```

初回認可:

```bash
/schedule auth
```

予定一覧:

```bash
/schedule list --date 2026-03-23
```

予定追加:

```bash
/schedule add --start 2026-03-23T09:00:00+09:00 --end 2026-03-23T10:00:00+09:00 定例会議
```

# LICENSE

このソフトウェアは MIT ライセンスの下で提供されます。
詳細については [LICENSE](./LICENSE) ファイルを参照してください。

# Author

mikoto2000 <mikoto2000@gmail.com>
