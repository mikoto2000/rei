# 設定ガイド

[README に戻る](../README.md) · [操作ガイド](usage.md)

初回の起動手順は README を参照してください。このページでは、設定の保存方法と機能ごとの接続先・動作の調整を説明します。

以下の環境変数の例は Bash 用です。PowerShell では `$env:変数名 = '値'` の形式で設定してください。設定後は Rei を再起動してください。

`<rei-data-dir>` は [README の保存先](../README.md#設定とデータの保存先) を表します。

## 外部設定ファイル

Rei は起動時に、組み込みの `application.yaml` に加えて、グローバル Rei Data Directory 配下の `<rei-data-dir>/application.yaml` を自動で読み込みます。ファイルが存在しない場合は無視されます。

設定の優先順位:

1. 環境変数
2. `<rei-data-dir>/application.yaml`
3. 組み込み `application.yaml`

外部設定ファイルのパス確認:

```text
/config path
```

テンプレート作成:

```text
/config init
```

`/config init --force` は既存ファイルを上書きするため、設定を作り直すときだけ使用してください。`/config init` は `<rei-data-dir>/application.yaml` と同じディレクトリに `<rei-data-dir>/additional-system-prompt.md` も作成します。
この Markdown ファイルに書いた内容は、既定の system prompt の末尾へ追記されます。空の場合は何も追加されません。

例:

```yaml
spring:
  ai:
    openai:
      base-url: http://127.0.0.1:11434
      chat:
        options:
          model: qwen3.5:122b
      image:
        options:
          model: gpt-image-1

rei:
  llm:
    max-output-tokens: 8192
    output-limit:
      max-replans-per-goal: 2
      max-subgoals-per-replan: 8
      max-llm-calls-per-run: 30
  image:
    output-directory: ${rei.data-dir}/images
    size: 1024x1024
    response-format: auto
    timeout-seconds: 300
  web-search:
    enabled: true
  interest:
    enabled: true
    notification-enabled: true
    notification-cron: "0 0 12 * * *"
  feed:
    briefing-max-items: 3
```

## OpenAI 互換 API

利用する機能に合わせて設定してください。通常の対話には接続先・API キー・チャットモデルを指定します。文書検索には埋め込みモデル、画像生成には画像生成モデルが別途必要です。

```bash
export REI_OPENAI_BASE_URL=https://api.openai.com
export REI_OPENAI_API_KEY=your-api-key
export REI_OPENAI_CHAT_MODEL=gpt-5.4
export REI_OPENAI_EMBEDDING_MODEL=text-embedding-3-small
export REI_OPENAI_IMAGE_MODEL=gpt-image-1
```

`REI_OPENAI_BASE_URL` には OpenAI 互換 API のベース URL を設定してください。
サーバーによっては `https://host/v1` まで含める構成が必要です。

環境変数:

| 変数 | 要否 | デフォルト | 説明 |
| --- | --- | --- | --- |
| `REI_OPENAI_BASE_URL` | 接続先に合わせて指定 | `http://192.168.1.50:11434` | OpenAI 互換 API のベース URL |
| `REI_OPENAI_API_KEY` | 必須 | `dummy-key` | API キー |
| `REI_OPENAI_CHAT_MODEL` | 利用モデルに合わせて指定 | `qwen3.5:122b` | chat 用モデル名 |
| `REI_OPENAI_EMBEDDING_MODEL` | 文書検索時に指定 | `qwen3-embedding:8b` | embedding 用モデル名 |
| `REI_OPENAI_IMAGE_MODEL` | 画像生成時必須 | `gpt-image-1` | 既定接続先で使う画像生成モデル名 |
| `REI_IMAGE_RESPONSE_FORMAT` | 任意 | `auto` | 画像生成 API に `response_format` を送るかを制御。`auto`, `b64_json`, `none` |
| `REI_IMAGE_TIMEOUT_SECONDS` | 任意 | `300` | 画像生成 API の読み取りタイムアウト秒数 |

## embedding・rerank の接続先

embedding はチャットとは別の接続先・API キーを指定できます。URL とキーが空の場合は、
それぞれ `spring.ai.openai.base-url` と `spring.ai.openai.api-key` を引き継ぎます。

```yaml
spring:
  ai:
    openai:
      embedding:
        base-url: ${REI_OPENAI_EMBEDDING_BASE_URL:}
        api-key: ${REI_OPENAI_EMBEDDING_API_KEY:}
        embeddings-path: ${REI_OPENAI_EMBEDDING_PATH:/v1/embeddings}
        options:
          model: ${REI_OPENAI_EMBEDDING_MODEL:qwen3-embedding:8b}

rei:
  rerank:
    base-url: ${REI_RERANK_BASE_URL:}
    api-key: ${REI_RERANK_API_KEY:}
    model: ${REI_RERANK_MODEL:}
    path: ${REI_RERANK_PATH:/v1/rerank}
```

例えば embedding を `http://localhost:8001`、rerank を `http://localhost:8002` に分けるには、
`REI_OPENAI_EMBEDDING_BASE_URL` と `REI_RERANK_BASE_URL` にそれぞれの URL を設定します。
各パスには API のパスを指定します。ベース URL に `/v1` を含める場合はパスを `/embeddings`、`/rerank` に変更してください。

rerank は `base-url` を指定すると有効になり、`model` の指定が必須です。
API キーは独立しており、空の場合は Authorization ヘッダーを送りません。
API には `model`、`query`、文字列配列の `documents` を POST します。
レスポンスは全候補の `index`（0 始まり）と `relevance_score` を持つ `results` 配列を想定します。
文書検索では、候補チャンクを文書単位で集約し、その本文を rerank に送ってから上位件数を選びます。
表示する `score` は従来の検索スコアを維持し、順位だけを変更します。
未設定時や API エラー・不正な応答時には従来の順位を使います。接続タイムアウトは 10 秒、読み取りタイムアウトは 30 秒です。
Web 検索の順位には適用しません。

## 機能別 LLM 設定

LLM を利用する機能ごとに、既定の `spring.ai.openai` とは別の OpenAI 互換 API サーバーとモデルを指定できます。
`base-url` を空にした機能は、従来どおり既定の LLM 設定を使います。
機能別に指定した LLM サーバーへの `call` / `stream` が失敗した場合は、`spring.ai.openai` の既定 LLM へフォールバックします。
ただし、`computer-use` と `computer-use-planner` はフォールバックせず、解析に失敗した場合は Computer Use タスクを `MODEL_ERROR` で終了します。
機能別 `model` を指定している場合、フォールバック時はそのモデル名を既定 LLM へ引き継がず、既定 LLM 側のデフォルトモデルを使います。

```yaml
rei:
  llm:
    max-output-tokens: ${REI_LLM_MAX_OUTPUT_TOKENS:8192}
    output-limit:
      max-replans-per-goal: ${REI_LLM_OUTPUT_LIMIT_MAX_REPLANS_PER_GOAL:2}
      max-subgoals-per-replan: ${REI_LLM_OUTPUT_LIMIT_MAX_SUBGOALS_PER_REPLAN:8}
      max-llm-calls-per-run: ${REI_LLM_OUTPUT_LIMIT_MAX_LLM_CALLS_PER_RUN:30}
    features:
      chat:
        base-url: ${REI_LLM_CHAT_BASE_URL:}
        api-key: ${REI_LLM_CHAT_API_KEY:}
        model: ${REI_LLM_CHAT_MODEL:}
      search:
        base-url: ${REI_LLM_SEARCH_BASE_URL:}
        api-key: ${REI_LLM_SEARCH_API_KEY:}
        model: ${REI_LLM_SEARCH_MODEL:}
      bluesky-reply:
        base-url: ${REI_LLM_BLUESKY_REPLY_BASE_URL:}
        api-key: ${REI_LLM_BLUESKY_REPLY_API_KEY:}
        model: ${REI_LLM_BLUESKY_REPLY_MODEL:}
      image-prompt:
        base-url: ${REI_LLM_IMAGE_PROMPT_BASE_URL:}
        api-key: ${REI_LLM_IMAGE_PROMPT_API_KEY:}
        model: ${REI_LLM_IMAGE_PROMPT_MODEL:}
      image-generation:
        base-url: ${REI_LLM_IMAGE_GENERATION_BASE_URL:}
        api-key: ${REI_LLM_IMAGE_GENERATION_API_KEY:}
        model: ${REI_LLM_IMAGE_GENERATION_MODEL:}
      output-limit-planner:
        base-url: ${REI_LLM_OUTPUT_LIMIT_PLANNER_BASE_URL:}
        api-key: ${REI_LLM_OUTPUT_LIMIT_PLANNER_API_KEY:}
        model: ${REI_LLM_OUTPUT_LIMIT_PLANNER_MODEL:}
```

`max-output-tokens` は 1 回の LLM 呼び出しで生成させる最大トークン数です。
チャットモデルを使う機能では `rei.llm.features.<機能キー>.max-output-tokens` で共通値を上書きできます。ShowUI の位置特定は128トークン固定です。
出力上限に達した要求は、処理を分割して継続します。`output-limit` で再計画回数・分割数・呼び出し回数の上限を調整できます。

対応している機能キー:

| 機能キー | 対象 |
| --- | --- |
| `chat` | 通常チャット |
| `computer-use` | Computer Use の画像解析。ShowUI モードではクリック位置の特定 |
| `computer-use-planner` | ShowUI モードでの操作・対象画面の判断 |
| `search` | `/search` の回答生成 |
| `memory` | `/memory consolidate`、`/memory summarize` |
| `bluesky-reply` | Bluesky リプライ文生成 |
| `feed-summary` | RSS/Atom フィード要約 |
| `briefing` | 日次ブリーフィング生成 |
| `interest-discovery` | `/interest discover` の候補抽出 |
| `agent-skills` | Agent Skills の暗黙選択 |
| `output-limit-planner` | 出力上限到達時のサブゴール再計画 |
| `image-prompt` | 画像生成プロンプト生成 |
| `image-generation` | 画像生成 API |

主な環境変数（既定値は組み込み設定の場合）:

| 変数 | 説明 |
| --- | --- |
| `REI_LLM_CHAT_BASE_URL` / `REI_LLM_CHAT_API_KEY` / `REI_LLM_CHAT_MODEL` | 通常チャット用 |
| `REI_LLM_SEARCH_BASE_URL` / `REI_LLM_SEARCH_API_KEY` / `REI_LLM_SEARCH_MODEL` | 検索回答生成用 |
| `REI_LLM_MEMORY_BASE_URL` / `REI_LLM_MEMORY_API_KEY` / `REI_LLM_MEMORY_MODEL` | メモリ統合用 |
| `REI_LLM_BLUESKY_REPLY_BASE_URL` / `REI_LLM_BLUESKY_REPLY_API_KEY` / `REI_LLM_BLUESKY_REPLY_MODEL` | Bluesky リプライ用 |
| `REI_LLM_FEED_SUMMARY_BASE_URL` / `REI_LLM_FEED_SUMMARY_API_KEY` / `REI_LLM_FEED_SUMMARY_MODEL` | フィード要約用 |
| `REI_LLM_BRIEFING_BASE_URL` / `REI_LLM_BRIEFING_API_KEY` / `REI_LLM_BRIEFING_MODEL` | ブリーフィング用 |
| `REI_LLM_INTEREST_DISCOVERY_BASE_URL` / `REI_LLM_INTEREST_DISCOVERY_API_KEY` / `REI_LLM_INTEREST_DISCOVERY_MODEL` | 興味候補抽出用 |
| `REI_LLM_AGENT_SKILLS_BASE_URL` / `REI_LLM_AGENT_SKILLS_API_KEY` / `REI_LLM_AGENT_SKILLS_MODEL` | Agent Skills 選択用 |
| `REI_LLM_OUTPUT_LIMIT_PLANNER_BASE_URL` / `REI_LLM_OUTPUT_LIMIT_PLANNER_API_KEY` / `REI_LLM_OUTPUT_LIMIT_PLANNER_MODEL` | 出力上限到達時の再計画用 |
| `REI_LLM_IMAGE_PROMPT_BASE_URL` / `REI_LLM_IMAGE_PROMPT_API_KEY` / `REI_LLM_IMAGE_PROMPT_MODEL` | 画像生成プロンプト生成用 |
| `REI_LLM_IMAGE_GENERATION_BASE_URL` / `REI_LLM_IMAGE_GENERATION_API_KEY` / `REI_LLM_IMAGE_GENERATION_MODEL` | 画像生成 API 用 |

出力上限・再計画の環境変数:

| 変数 | デフォルト | 説明 |
| --- | --- | --- |
| `REI_LLM_MAX_OUTPUT_TOKENS` | `8192` | 1 回の LLM 呼び出しの最大出力トークン数 |
| `REI_LLM_OUTPUT_LIMIT_MAX_REPLANS_PER_GOAL` | `2` | 1 回の要求内で許可する再計画回数 |
| `REI_LLM_OUTPUT_LIMIT_MAX_SUBGOALS_PER_REPLAN` | `8` | Planner が返せる最大サブゴール数 |
| `REI_LLM_OUTPUT_LIMIT_MAX_LLM_CALLS_PER_RUN` | `30` | 1 回の要求内で許可する LLM 呼び出し回数 |

## Google Calendar と Google Tasks

Google Tasks は既定で有効です。利用しない場合は `REI_GOOGLE_TASK_ENABLED=false` を設定します。資格情報を用意した後、予定は `/schedule auth`、タスクは `/task auth` で認可してください。

Google Calendar または Google Tasks 連携を使う場合は、Google Cloud で Desktop app の OAuth クライアントを作成し、資格情報 JSON を `REI_GOOGLE_CREDENTIALS_PATH` に配置してください。

手順の概要:

1. Google Cloud Console で対象プロジェクトを作成または選択する
2. 利用する Google Calendar API / Google Tasks API を有効にする
3. OAuth 同意画面を設定する
4. `Credentials` から `OAuth client ID` を作成し、`Desktop app` を選ぶ
5. ダウンロードした JSON を `REI_GOOGLE_CREDENTIALS_PATH` に配置する

```bash
export REI_GOOGLE_CALENDAR_ENABLED=true
export REI_GOOGLE_CREDENTIALS_PATH="/absolute/path/to/google-credentials.json"
export REI_GOOGLE_CALENDAR_TIME_ZONE=Asia/Tokyo  # タイムゾーン
```

Google Calendar と Google Tasks の資格情報と認可情報は、デフォルトではグローバル Rei Data Directory 配下の `<rei-data-dir>` に保存されます。必要に応じて `REI_GOOGLE_CREDENTIALS_PATH` と `REI_GOOGLE_TOKENS_DIR` で上書きできます。

主な環境変数（既定値は組み込み設定の場合）:

| 変数 | 要否 | デフォルト | 説明 |
| --- | --- | --- | --- |
| `REI_GOOGLE_CALENDAR_ENABLED` | 任意 | `false` | Google Calendar 連携を有効化 |
| `REI_GOOGLE_CREDENTIALS_PATH` | 利用時必須 | `${rei.data-dir}/google-credentials.json` | OAuth クライアント資格情報 JSON |
| `REI_GOOGLE_TOKENS_DIR` | 任意 | `${rei.data-dir}/google-tokens` | OAuth token 保存先 |
| `REI_GOOGLE_CALENDAR_DEFAULT_CALENDAR_ID` | 任意 | `primary` | 既定カレンダー ID |
| `REI_GOOGLE_CALENDAR_TIME_ZONE` | 任意 | 空 | オフセットなし日時の解釈に使うタイムゾーン |

テンプレートから設定ファイルを作成した場合、資格情報とトークンの保存先は `google-calendar-credentials.json` / `google-calendar-tokens` です。設定ファイルの `rei.google.credentials-path` と `rei.google.tokens-directory` を確認してください。

### 認可の自動更新

Google Calendar と Google Tasks は OAuth 認証情報・トークン・更新処理を共有します。
認可後は、Rei の起動中に保存済みのアクセストークンの期限を5分ごとに確認し、
残り5分以内（期限切れを含む）ならリフレッシュします。更新結果は既存のトークン保存先に保存されます。
期限が不明な場合も更新を試みます。未認可・リフレッシュトークン未取得・両連携が無効の場合はスキップし、
バックグラウンドでブラウザ認証は開始しません。通信エラー等の更新失敗はログに記録し、次回確認時に再試行します。
認可が失効した場合は `/schedule auth`（Calendar 有効時）または `/task auth` で再認可してください。
手動更新の `/schedule refresh-token` も引き続き使用できます。

| 環境変数 | デフォルト | 用途 |
| --- | --- | --- |
| `REI_GOOGLE_TOKEN_REFRESH_ENABLED` | `true` | 自動更新の有効・無効 |
| `REI_GOOGLE_TOKEN_REFRESH_CHECK_INTERVAL` | `5m` | 期限の確認間隔 |
| `REI_GOOGLE_TOKEN_REFRESH_ADVANCE` | `5m` | 有効期限の何分前から更新するか |

Rei の停止中は更新されません。

## Web 検索

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

主な環境変数（既定値は組み込み設定の場合）:

| 変数 | 要否 | デフォルト | 説明 |
| --- | --- | --- | --- |
| `REI_WEB_SEARCH_ENABLED` | 任意 | `true` | Web 検索を有効化 |
| `REI_WEB_SEARCH_TIMEOUT_SECONDS` | 任意 | `10` | HTTP タイムアウト秒数 |
| `REI_WEB_SEARCH_MAX_RESULTS` | 任意 | `5` | 取得する最大件数 |
| `REI_WEB_SEARCH_DUCKDUCKGO_BASE_URL` | 任意 | `https://html.duckduckgo.com/html/` | DuckDuckGo 検索 URL |
| `REI_WEB_SEARCH_BRAVE_BASE_URL` | Brave 利用時任意 | `https://api.search.brave.com/res/v1/web/search` | Brave Search API URL |
| `REI_WEB_SEARCH_BRAVE_API_KEY` | Brave 利用時必須 | 空 | Brave Search API キー |

## RSS フィード

RSS/Atom フィードは `<rei-data-dir>/application.yaml` または環境変数で設定できます。保存するのは本文ではなく、タイトル、URL、公開日時、取得日時などの最小メタデータだけです。

```yaml
rei:
  feed:
    briefing-max-items: 3
    cron: "0 0 4 * * *"
```

主な環境変数（既定値は組み込み設定の場合）:

| 変数 | 要否 | デフォルト | 説明 |
| --- | --- | --- | --- |
| `REI_FEED_BRIEFING_MAX_ITEMS` | 任意 | `3` | `/briefing today` と `feed summary` で各フィードから扱う最大記事数 |
| `REI_FEED_CRON` | 任意 | `0 0 4 * * *` | 記事を定期更新する日時。既定では毎日 4:00 |

## 興味に応じた通知

過去の会話履歴から興味がありそうな話題を抽出し、Web 検索した有益情報を定期表示する場合は、以下の通知設定を有効にします。通知は標準出力へ直接流し、通知文自体は会話メモリに保存されません。

`rei.interest.cron` は興味更新の定期抽出ジョブ、`rei.interest.notification-cron` は通知ジョブです。周期は独立して設定できます。

```yaml
rei:
  interest:
    enabled: true
    cron: "0 0 7 * * *"
    notification-enabled: true
    notification-cron: "0 0 12 * * *"
```

主な環境変数（既定値は組み込み設定の場合）:

| 変数 | 要否 | デフォルト | 説明 |
| --- | --- | --- | --- |
| `REI_INTEREST_ENABLED` | 任意 | `false` | 興味更新の定期抽出ジョブを有効化 |
| `REI_INTEREST_CRON` | 任意 | `0 0 7 * * *` | 興味を抽出する日時 |
| `REI_INTEREST_NOTIFICATION_ENABLED` | 任意 | `false` | 興味更新通知ジョブを有効化 |
| `REI_INTEREST_NOTIFICATION_CRON` | 任意 | `0 0 12 * * *` | 通知する日時 |

## MCP

MCP サーバーを有効にする場合は、JSON 設定ファイルを用意してください。

```bash
export REI_MCP_ENABLED=true
export REI_MCP_STDIO_SERVERS_CONFIG="file:/absolute/path/to/mcp-servers.json"
```

`REI_MCP_STDIO_SERVERS_CONFIG` には `file:` 付きの URI を指定します。`<rei-data-dir>/mcp-servers.json` は Claude Desktop 互換形式です。

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

主な環境変数（既定値は組み込み設定の場合）:

| 変数 | 要否 | デフォルト | 説明 |
| --- | --- | --- | --- |
| `REI_MCP_ENABLED` | 任意 | `false` | MCP client を有効化 |
| `REI_MCP_STDIO_SERVERS_CONFIG` | 利用時必須 | `file:${rei.data-dir}/mcp-servers.json` | MCP サーバー定義ファイル |

## Bluesky 投稿・自動返信

Bluesky 投稿機能と、対象ユーザーへの確率リプライ機能を利用できます。

```bash
export REI_BLUESKY_ENABLED=true
export REI_BLUESKY_HANDLE=your-handle.bsky.social
export REI_BLUESKY_APP_PASSWORD=xxxx-xxxx-xxxx-xxxx
export REI_BLUESKY_MAX_POST_LENGTH=300
export REI_BLUESKY_TIMEOUT_SECONDS=30
```

`application.yaml`（または `<rei-data-dir>/application.yaml`）に `rei.bluesky.reply` を定義すると、対象ユーザーの投稿を定期チェックし、条件を満たした投稿に自動返信します。

```yaml
rei:
  bluesky:
    timeout-seconds: 30
    reply:
      enabled: true
      dry-run: false
      check-interval-seconds: 300
      fetch-limit: 30
      exclude-replies: true
      exclude-reposts: true
      max-post-age-minutes: 120
      generation-timeout-seconds: 1200
      users:
        - handle: "alice.bsky.social"
          probability: 0.25
          max-replies-per-day: 3
```

補足:

- `dry-run: true` の場合、投稿 API は呼ばずログ出力のみ行います。
- 除外条件（repost/reply/古い投稿/既返信）と確率判定、日次上限判定を通過した投稿のみ返信します。
- `timeout-seconds` は Bluesky API への各 HTTP リクエストのタイムアウトです。
- `generation-timeout-seconds` は自動返信文を LLM で生成するときのタイムアウトです。
- 自動返信と手動の AI 返信では、LLM 本文に `</think>` が含まれる場合、最後の閉じタグより後だけを返信に使います（開始タグが欠ける場合にも対応）。未完了の think タグが残る場合や返信本文が空になる場合は、生成失敗として投稿しません。タグのない思考文は、この処理では判別できません。
- 前回の自動返信チェックが実行中の場合、次の定期実行はスキップして WARN ログへ理由を出力します。
- 自動返信と手動返信は、送信前に対象投稿の URI を SQLite に記録して重複送信を防ぎます。同じ DB を使う複数プロセス間でも有効で、URL 指定と URI 指定も同じ対象として扱います。既に返信した投稿への手動返信もスキップします。
- 送信中の通信例外などで成否が不明になった対象は、再起動後も再送を抑止します。送信直前の終了で未送信となる場合もあるため、再試行が必要なときは Bluesky 上の投稿状況を確認してください。API が明示的に失敗を返した場合は送信予約を解除します。
## 記憶の保存タイムアウト

`REI_MEMORY_CONFLICT_TIMEOUT_SECONDS`（既定: `60`）は、記憶候補を保存する前の競合確認の制限時間です。タイムアウトした候補は保存されず、警告が表示されます。
