# 操作ガイド

[README に戻る](../README.md) · [設定ガイド](configuration.md)

以下のコマンドは Rei の対話プロンプトに入力します。日付・パス・ID は手元の値に置き換えてください。Google 連携などの事前設定は設定ガイドを参照してください。

## 画像の入力

Rei の通常のチャット入力に、バッククォートで囲んだ `@file:パス` を含めると、画像ファイルを添付できます。

```text
この画像を説明して `@file:C:\Users\your-name\Pictures\sample.png`
```

クリップボードにコピーした画像を添付する場合は、バッククォートで囲んだ `@clipboard` を使います。

```text
この画像を説明して `@clipboard`
```

どちらも前後のバッククォート（`` ` ``）が必要です。上記の例は Rei の対話プロンプトに直接入力してください。

## モデル

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

## 画像生成

プロンプトから画像を生成してローカルファイルへ保存します。

```text
/image generate 猫がキーボードを叩いているイラスト
/image generate --size 1024x1024 --output ./out/cat.png 猫がキーボードを叩いているイラスト
/image generate --model gpt-image-1 夕暮れの港の水彩画
/image generate --raw "A watercolor painting of a harbor at dusk"
```

`--output` を省略した場合は `rei.image.output-directory` 配下に `image-<yyyyMMdd-HHmmss>.png` として保存します。
`--size` を省略した場合は `rei.image.size` を使用します。
既定では入力文を `rei.llm.features.image-prompt` のチャット LLM で画像生成向けプロンプトへ変換してから画像生成 API に渡します。
`--raw` を指定すると、入力文を変換せずそのまま画像生成 API に渡します。

```yaml
rei:
  image:
    output-directory: ${REI_IMAGE_OUTPUT_DIRECTORY:${rei.data-dir}/images}
    size: ${REI_IMAGE_SIZE:1024x1024}
    response-format: ${REI_IMAGE_RESPONSE_FORMAT:auto}
    timeout-seconds: ${REI_IMAGE_TIMEOUT_SECONDS:300}
    prompt-enhancement:
      enabled: ${REI_IMAGE_PROMPT_ENHANCEMENT_ENABLED:true}
```

プロンプト生成だけ別サーバーを使う場合は `rei.llm.features.image-prompt` を設定します。
画像生成 API だけ別サーバーを使う場合は `rei.llm.features.image-generation` を設定します。
未設定の場合は `spring.ai.openai` の既定接続先を使い、機能別接続先が失敗した場合は既定接続先へフォールバックします。
`response-format` は `auto` の場合、`gpt-image-*` モデルや既定 OpenAI 経路では `response_format` を送らず、ローカル OpenAI 互換サーバーで必要な場合は `b64_json` を指定します。
`timeout-seconds` は画像生成 API の読み取りタイムアウトです。OpenAI 公式や重いローカルモデルで時間がかかる場合は大きくしてください。
`prompt-enhancement.enabled` を `false` にすると、既定でも入力文をそのまま画像生成 API に渡します。

## Google Calendar

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

## タスク管理

Google Tasks の既定のタスクリストを操作します。[Google 連携を設定](configuration.md#google-calendar-と-google-tasks)し、初回に `/task auth` で認可してください。完了・削除には `/task list` に表示される ID を指定します。

現在、優先度とタグの保存・絞り込みには対応していません。

追加:

```text
/task add --due 2026-04-03 提案書作成
```

一覧:

```text
/task list
/task list --due-before 2026-04-03
```

完了・削除:

```text
/task done <ID>
/task delete <ID>
```

## RSS Feed

フィード登録:

```text
/feed add --name Publickey https://www.publickey1.jp/atom.xml
```

OPML からの一括登録:

```text
/feed import-opml ~/Downloads/subscriptions.opml
/feed import-opml "C:\Users\your-name\Downloads\subscriptions.opml"
```

OPML に含まれるフィードを一括登録します。同じ URL はスキップし、無効な URL や登録失敗があっても残りを処理します。結果は `Imported` / `Skipped` / `Failed` の件数と、重複・失敗の詳細（各20件まで）を表示します。

相対パスは現在のプロジェクトを基準に解決し、`~/` はホームディレクトリへ展開します。ファイルは最大10 MiBです。カテゴリ階層は保存されません。登録後に `/feed update` で記事を取得してください。

登録済みフィード一覧:

```text
/feed list
```

フィード更新:

```text
/feed update
/feed update 1
```

記事 ID 一覧の確認:

```text
/feed item list
/feed item list --from 2026-04-21T00:00:00Z --to 2026-04-22T09:00:00Z
```

記事要約とブリーフィング要約:

```text
/feed item summarize 42
/feed summary
```

`/feed summary` は結果を画面に表示し、既存の音声通知設定を使って読み上げます。

## 日次ブリーフィング

```text
/briefing today
```

その日の予定、未完了タスク、関連文書、新着 RSS 記事、注意点、次アクションをまとめて表示します。RSS セクションには、昨日 00:00 から現在までに公開された記事を公開日時の降順で表示し、0 件なら `昨日 00:00 以降の新着記事はありませんでした` と表示します。

## リマインド

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

## 文書の埋め込み

embedding は既定で有効です。環境変数 `REI_EMBEDDING_ENABLED=false`、または外部設定の `rei.embedding.enabled: false` で無効化できます（再起動後に反映）。無効時は `/embed` がヘルプ・補完から消え、実行できなくなります。`/search` は Web 検索のみとなり、ブリーフィングにも関連文書を含めません。保存済みの文書・ベクトルは削除されず、再度有効化すると利用できます。

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

現状のベクトルストアは、グローバルなデータ保存先の `<rei-data-dir>/vectorstore.db` に保存されます。アプリ本体の履歴やタスクなどで使う `<rei-data-dir>/memory.db` とは別ファイルです。



## 記憶管理

`/memory` は、会話履歴から抽出した記憶の確認・検索・削除・エクスポートを行うコマンドです。

保存済みの記憶を一覧表示:

```text
/memory list
```

記憶を検索:

```text
/memory search "Google Task"
/memory search --limit 5 "Bluesky"
```

不要な記憶を論理削除:

```text
/memory forget <memory-id>
```

記憶を Markdown と JSONL でエクスポート:

```text
/memory export
/memory export --dir ./memory-export
```

会話履歴から記憶候補を抽出:

```text
/memory consolidate
/memory consolidate --approve
```

`/memory consolidate` は、`--approve` を付けない場合は候補を表示するだけで保存しません。保存する場合は `--approve` または `--save` を付けて実行します。

会話履歴から要約を作成:

```text
/memory summarize
/memory summarize --approve
```

`/memory summarize` も `--approve` を付けない場合は要約を表示するだけです。保存する場合は `--approve` または `--save` を付けて実行します。

## 検索

```text
/search spring ai latest
/search --source /absolute/path/to/spec.md spring ai tools
```

`/search` はベクトル検索結果と Web 検索結果をまとめて回答します。Web 側は検索結果の snippet をそのまま使うのではなく、上位ページの本文を取得したうえで、クエリ展開、重複 URL 除外、一次情報優先の再ランキングを行います。

Web 検索が無効、API キー未設定、不正 API キーなどで失敗した場合は、Web 検索をスキップしてベクトルストアの内容だけで回答します。その場合は出力に `[web search skipped] ...` が表示されます。

## 会話履歴

`/history` は永続 Session 一覧、`/history show <sessionId>` は詳細と Turn を表示します。どちらも `--limit`（1〜100、既定50）と `--cursor` に対応します。従来の選択中プロジェクトの直近50メッセージは `/history show` で表示します。AI の実行中にも利用できます。[Session API・移行に関する仕様](session-history.md)も参照してください。

```text
/history
/history list --project "MaCa Editor"
/history list --limit 50 --offset 50
/history show chat:test --project "MaCa Editor" --last 100
/history show --all
/history search Working Set 候補削減
/history search --current "Working Set"
/history search --all --limit 20 "OAuth OBO"
/history --help
```

`--project` は登録名またはProjectIdです。同名の場合はProjectIdを指定してください。検索は既定で現在プロジェクトを優先し、不十分なら他プロジェクトも検索します。検索件数と本文の表示量には上限があります。
## URL の要約

```text
/summarize https://example.com/article
/summarize
```

URL を指定するとバックグラウンドで要約します。引数なしでは、選択中のプロジェクトで最後に成功した要約を再表示します。処理中の場合は、その状態も表示します。保存された要約は再起動後も参照できます。

要約完了後に「今要約した記事を、元 URL とともに Bluesky に投稿して」と指示できます。AI は `getLastSummary` で実行元プロジェクトの最後に成功した要約と元 URL を取得します。セッション変更・再起動後も参照でき、要約が未完了の場合は直前の成功結果が対象になります。

画像生成もバックグラウンドで動きます。`/image "猫のイラスト"` という省略形も使用できます。`/image` 単体では使い方を表示します。進行状況は `/runs` で確認してください。プロジェクトを切り替えても、要約結果と会話履歴は開始元のプロジェクトに保存されます。
