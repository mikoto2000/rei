# Rei

「玲」は、 AI 秘書になる予定のモノです。

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
/schedule add --start 2026-03-23T09:00:00Z --end 2026-03-23T10:00:00Z 定例会議
```

# LICENSE

このソフトウェアは MIT ライセンスの下で提供されます。
詳細については [LICENSE](./LICENSE) ファイルを参照してください。

# Author

mikoto2000 <mikoto2000@gmail.com>
