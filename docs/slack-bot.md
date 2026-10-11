# Slack Bot（Phase 1 / Phase 2）

Slack をれいの既存 Session／Run に接続する opt-in の会話チャネルです。
DM の新規投稿、許可チャンネルのメンション、開始済みスレッドの継続に対応します。
他 Bot の依頼、Slack 内の承認、任意 Shell コマンド、履歴の一括取得には対応しません。
設計調査は [Slack 設計記録](slack-bot-design.md)を参照してください。

## Slack App の作成

1. Slack の [Your Apps](https://api.slack.com/apps) で対象 Workspace に App を作成します。
2. Basic Information の App-Level Tokens で `connections:write` のみを持つトークンを作成します。
3. Socket Mode を有効にします。Request URL、公開サーバー、ポート開放は不要です。
4. OAuth & Permissions に下記 Bot Token Scope を設定します。
5. Event Subscriptions を有効にし、Subscribe to bot events に下記イベントを設定します。
6. Workspace に App をインストールします。Scope の変更後は再インストールします。
7. 会話するチャンネルに Bot を招待します。App Home で Bot へのメッセージを許可します。

| Bot Scope | 用途 |
| --- | --- |
| `chat:write` | 元スレッドへの返信 |
| `app_mentions:read` | `app_mention` での新規依頼 |
| `im:history` | `message.im` による DM 受信 |
| `channels:history` | `message.channels` による公開チャンネルのスレッド継続 |
| `users:read` | 許可 User ID が人間かを接続時に確認 |

購読イベントは `app_mention`、`message.im`、`message.channels` です。
private channel も使用する場合に限り `groups:history` と `message.groups` を追加してください。
`chat:write.public`、`commands`、`users:read.email`、ファイル権限、履歴取得 API 権限の追加は不要です。
Bot は招待済みの許可チャンネルでのみ使用してください。Slack が配信する未許可イベントは
認可境界で破棄し、Run、会話履歴、受信台帳には渡しません。

Scope とイベントの根拠:
[Socket Mode](https://docs.slack.dev/tools/java-slack-sdk/guides/socket-mode/)、
[app_mention](https://docs.slack.dev/reference/events/app_mention/)、
[message.im](https://docs.slack.dev/reference/events/message.im/)、
[message.channels](https://docs.slack.dev/reference/events/message.channels/)、
[users.info](https://docs.slack.dev/reference/methods/users.info/)、
[chat.postMessage](https://docs.slack.dev/reference/methods/chat.postMessage/)。

## れいの設定

`/config path` で外部設定ファイルの場所を確認します。
以下の ID はダミーです。Project の値には既存 Project API、または
`<rei-data-dir>/projects.json` の `projects[].id` にある登録済み UUID を指定します。
Shell の `/project add`／`/project cd` で作業先を登録・選択してから ID を確認してください。
`/project list` の表示はパスです。プロジェクト名やパスは Slack の設定値には使いません。

```yaml
rei:
  slack:
    enabled: true
    allowed-workspace-ids: [T000EXAMPLE]
    allowed-user-ids: [U000EXAMPLE]
    allowed-channel-ids: [C000EXAMPLE, D000EXAMPLE]
    dm-project-id: 00000000-0000-4000-8000-000000000001
    channel-projects:
      C000EXAMPLE: 00000000-0000-4000-8000-000000000001
```

Workspace、User、Channel の **全て**が許可リストに一致する必要があります。
空リストは誰も許可しません。DM の `D...` Channel ID も明示的に許可します。
Slack のプロフィールのメニューから User ID、会話の詳細やメッセージのリンクから
Channel ID、Workspace URL／管理情報から Team ID を確認してください。
Slack Connect など別 Workspace の送信者も ID の許可判定を受けます。
本 Phase は1インストール／1 Workspace です。Bot Token の認証結果と受信 Team ID を照合します。
許可ユーザーは接続時に `users.info` で確認し、Bot／App／削除済みユーザーを除外します。
設定変更時は Backend を再起動してください。

トークンは Backend プロセスの環境変数に設定します。
`SLACK_BOT_TOKEN` はインストールで得た Bot User OAuth Token、
`SLACK_APP_TOKEN` は `connections:write` を持つ App-Level Token です。
トークンを YAML、Git、Run のメッセージ、Slack の本文に記載しないでください。
環境変数を設定する際も、シェル履歴に実値が残らない秘密情報管理手段を使用してください。

通常の Shell 起動では Slack 接続は増えません。専用 Backend を起動します。
軽量 CLI と共用する場合は、`REI_API_KEY` と同じ `REI_DATA_DIR` を設定して
`rei.bat --mode=server`（Linux/macOS は `./rei.sh --mode=server`）で起動します。
`--mode=server` も Slack の Backend 起動条件を満たし、Slack 接続はこの一つのプロセスが管理します。

```powershell
java -jar target/rei-0.0.1-SNAPSHOT.jar --backend
```

既存 HTTP API を使う場合は従来どおり `REI_API_KEY` も設定します。
Socket Mode 自体には HTTP API の有効化は不要です。
同じデータディレクトリで複数 Backend が起動しても `slack-backend.lock` により
Slack 接続を所有できるのは1プロセスだけです。
終了はプロセスへの通常の終了要求／Ctrl+C を使います。

## 会話

DM では通常の新規メッセージが依頼を開始します。Bot はその投稿のスレッドに返信します。
公開チャンネルでは `@れい 調査してください` のようなメンションで開始します。
開始済みスレッドでは許可ユーザーの追加発言をメンションなしで受け付けます。
未参加スレッドの発言は、れいへの明示的なメンションがある場合だけ新規依頼として受け付けます。
DM の未参加返信スレッドやグループ DM は対象外です。

1スレッド = 1 Session です。識別は Team ID／Channel ID／親 Thread TS です。
Project はスレッド作成時に固定し、設定を変えても既存スレッドを他 Project に付け替えません。
CLI／Shell の Project 選択や通知 Session は変更しません。
Run は既存の ProjectRunQueue に従い、同 Project の排他的依頼を直列化します。
別 Project の処理は既存の並列実行枠を使います。

同じスレッドで以下の **メッセージ**を送信できます（Slack の Slash Command 登録は不要）。

```text
rei status
rei cancel
```

チャンネルの未参加スレッドでは `@れい rei status` のように送ります。
互換エイリアス `/rei-status`／`/rei-cancel` も対応しますが、通常のメッセージ形式を推奨します。
状態確認とキャンセル以外の文字列は通常の依頼本文です。
キャンセルの受付と、実行キューからの退出を確認した停止完了は別の通知になります。

## 承認と再開

既存 Policy／Permission、モデル予算、Tool、Planning Loop をそのまま使用します。
Slack 経由でツール権限を広げません。
既存 Tool Permission は既定で無効です。承認を必要とする運用では同じ外部設定に
以下を追加し、能力分類を確認してください。

```yaml
rei:
  tool-permission:
    enabled: true
    auto-approve: [READ, NETWORK_READ]
    denied: [DESTRUCTIVE]
```

ツールが承認を要求すると Slack に承認待ちを表示し、既存の承認レコードと
チェックポイントを使います。既存 RunStatus には WAITING_APPROVAL がないため、
Slack の表示状態として管理します。

認可された UI／Shell で対象 Project を選び、`/approval list`、`/approval show <id>`
で内容を確認し、`/approval approve <id>` または `/approval deny <id>` を使ってください。
承認後は既存のチェックポイント確認・再開を実施します。
Slack を所有する Backend 上で同じ Session のチェックポイントを再開すると、
再開 Run の回答も元の Slack スレッドへ返ります。この操作には既存の認証された UI／HTTP API を使います。
承認・再開 UI を利用する運用では `REI_API_KEY` で既存 HTTP API も有効にしてください。
別プロセスの standalone Shell で再開した Run のイベントは Backend に配信されないため、
その再開結果の Slack 自動通知は対象外です。Shell による既存承認レコードの操作は利用できます。
Slack の通常メッセージや Bot の返信による承認・自動再開はありません。
[権限ガイド](tool-permission.md)と[チェックポイント再開](persistent-checkpoint-resume.md)を参照してください。

## 復旧・送信の制約

`<rei-data-dir>/slack.db` に受付、スレッドと Session／Project の対応、受付と Run の対応、
Outbox、送信状態を保存します。バックアップ対象にも含まれます。
受付を保存した後に ACK し、LLM は非同期で実行します。
保存失敗時は ACK せず、Slack の再送で再受付を試みます。
同一イベント ID と同一 Team／Channel／投稿 TS の両方で重複を抑えます。

再起動後は未処理の ACCEPTED 受付と PENDING Outbox を処理します。
最終回答は既存の永続化された完了 Turn を Session／Run で検索し、実行キューからの退出後に返信します。
再開 Run の対応も別途保存し、最初のイベントと Run の対応を上書きしません。
既存 Run を再接続のために再実行しません。
dispatch の前に Run ID と DISPATCHING 状態を保存し、その後に既存キューへ投入します。
この間に停止した場合、実際の投入・実行結果が分からないことがあります。
不確かな dispatch は UNKNOWN として人間の確認・チェックポイント再開を要求します。
この保守的な停止により、処理が未実行でも自動では再実行しない場合があります。
キュー上限による確実な受付拒否は既存 Run の失敗として終端化します。
投入が不確かでキューに存在しない Run は UNKNOWN とし、QUEUED のまま残しません。

Outbox は送信前に SENDING を保存します。成功レスポンスを確認したら SENT、
確実な認証／宛先等の拒否なら FAILED、タイムアウト・5xx・不明応答なら UNKNOWN にします。
再起動後に残る SENDING も結果不明として扱い、自動再投稿しません。
429／明示的レート制限は Retry-After に従い PENDING に戻します。
同一チャンネルには最大1回／秒を目安に送信します。
Slack 側の厳密な exactly-once は保証しません。
UNKNOWN の解除・強制再送 UI はこの Phase では提供しません。
運用者が Slack と Outbox の両方を確認し、必要なら手動で内容を伝えてください。
台帳の削除は受信の重複防止記録も失わせるため、通常運用では削除しないでください。

回答は改行を保持し、3000 UTF-16 コード単位以下の投稿に分割します。
基本的な強調・取消線を mrkdwn に変換し、入力由来の `<@...>` などをエスケープします。
Markdown 全体の完全な変換や分割したコードブロックの再構成には対応していません。
トークン単位の投稿、思考、ツールの生データ、例外全文の投稿は行いません。

## 障害診断

接続失敗は Slack だけに閉じ込め、Backend の通常機能を停止させません。
初期接続失敗は30秒後に再試行し、接続後の維持・再接続は SDK が管理します。
ログにトークンや例外全文を出さず、固定の診断コードを使用します。

| 診断／症状 | 確認すること |
| --- | --- |
| トークン不足 | Backend に2つの環境変数を設定したか |
| WORKSPACE_NOT_ALLOWED | Bot Token の Team ID が許可されているか |
| USER_VALIDATION_FAILED | `users:read`、ID、再インストール、API 接続 |
| NO_HUMAN_USERS | 許可リストが空か、Bot／App ユーザーだけになっていないか |
| PROJECT_NOT_REGISTERED | 登録済み Project UUID を指定したか |
| ANOTHER_BACKEND | 同じ保存先の別 Backend が接続を所有していないか |
| AUTHENTICATION_FAILED | Bot Token の失効・誤り、対象 Workspace |
| CONNECTION_FAILED | App-Level Token、Socket Mode、DNS／外向き HTTPS／WebSocket |
| 返信がない | Team／User／Channel の全許可、イベント購読、Bot の招待、Outbox 状態 |
| 承認待ちのまま | 既存 UI の承認レコード／チェックポイントと Run の状態 |

SDK のログは既定で無効です。raw payload／HTTP debug／Socket URL のログを有効化しないでください。
Outbox や Session の本文には会話が含まれるため、データディレクトリを適切に保護してください。
Slack に依頼するユーザーの許可は、れいのツールを実行する信頼境界でもあります。
Bot からの新規依頼は受け付けません。Phase 2 の外部返信も、下記の個別設定と
送信済み依頼に一致する場合だけ、外部ツールの観測結果として取り込みます。

## 検証

単体・統合テストは Slack トークンを使わず、SDK の MethodsClient と接続を Fake に置き換えます。
実 Workspace での Socket 接続・Scope・実際のメッセージ往復は未検証です。
運用開始時は最小の許可リストで DM／メンション／スレッド継続／再起動／承認／キャンセルを確認してください。

## Phase 2：てんてんへの質問

人間が開始した既存 Run のモデルが `askExternalAgent` を選ぶと、管理者が登録した
Bot に明示的な質問を投稿します。標準では無効です。Phase 1 の設定に加えて、
同じ `rei.slack` 以下に次を設定します。ID はすべてダミーです。

```yaml
rei:
  slack:
    agent-collaboration:
      enabled: true
      allowed-workspace-ids: [T000EXAMPLE]
      allowed-channel-ids: [C000EXAMPLE]
      agents:
        tenten:
          workspace-id: T000EXAMPLE
          bot-user-id: U000TENTEN
          bot-id: B000TENTEN
          channel-id: C000EXAMPLE
          mention-user-id: U000TENTEN
          final-prefix: ""
      max-requests-per-run: 3
      max-follow-up-turns: 2
      max-delegation-depth: 1
      timeout-seconds: 300
```

Bot User ID (`U...`) と Bot ID (`B...`) は別です。てんてんのプロフィールの User ID と、
管理者が確認した `users.info` の `profile.bot_id` を使ってください。接続時にれい自身の
Bot と区別し、`users.info` の結果と設定値を照合します。てんてんを Phase 1 の
`allowed-user-ids` に入れないでください。このリストは引き続き人間専用です。
両 Bot を指定チャンネルに招待し、れい側の `message.channels`（private なら
`message.groups`）購読を維持します。Phase 1 の `users:read` と `chat:write` を再利用し、
新しい Scope や公開 Request URL は不要です。てんてん側が Bot のメンションを無視する
設定なら返信は来ません。これは実際のてんてんで確認が必要です。

`askExternalAgent(agent, question, expectedOutput?, timeoutSeconds?, followUpRequestId?)`
は `NETWORK_WRITE` です。既存 Policy / Permission が、解決済みの Bot・Workspace・
チャンネル・質問本文を判定します。宛先 ID はモデルの引数から変更できません。
確認が必要な運用では `tool-permission.enabled: true` を維持し、既存承認 UI を使います。
トークン、秘密、Session 履歴を質問に含めず、必要な短い質問だけ送ってください。
検証済みの Slack 接続が成立していない場合は、依頼の登録を拒否します。
トークン不足や接続設定の誤りで、期限処理のない WAITING を作ることはありません。
既知のトークン形式は伏せますが、任意の秘密を自動判定する保証はありません。

許可ユーザーから、例えば `@れい てんてんにこの設計の注意点を質問し、回答をまとめて`
と依頼します。LLM がツールを使った場合にだけ外部投稿が発生します。
送信先には `<@てんてんのUserID>`、質問、期待する出力、`slack-req-...` を送ります。
送信完了は回答完了ではありません。親 Run は `WAITING` となり、実行スレッド・
Project キュー・モデル呼び出しを解放します。既存 Dependency には `EXTERNAL_AGENT`
の待機を保存し、終端結果を既存の観測・スケジューラ処理に渡します。

返信は Workspace / Channel / 投稿の親 Thread TS / Bot ID / Bot User ID の対応を
照合し、編集・削除・別 Bot・人間・別スレッドを除外します。Bot の投稿から新しい
Session / Run は作りません。イベント ID と投稿 TS で重複を抑え、送信レスポンスより
先に届く返信も、認証済みの候補を一時保存して正確な親 TS が判明した後に照合します。
受理した返信は SQLite に保存してから Socket Mode の ACK を返します。

てんてんの最終回答形式はこの実装では未確認です。`final-prefix: ""` のままなら、
最初の返信・受付・進捗・無応答を完了と推測せず、期限まで最大32件を集めます。
期限後は `TIMED_OUT` と部分回答・不確実性を元の Run に返します。
実際のてんてんに明確な最終回答専用プレフィックスがあると運用者が確認した場合のみ、
`final-prefix` をその値に設定してください。完全一致の先頭文字列を検出して
`COMPLETED` にします。質問を単に引用する受付と同じ値は設定しないでください。

返信と状態は SystemMessage に昇格させず、`UNTRUSTED DATA` と表示した ToolResponse
として元の ToolCall に戻します。元の Project / Session / Run / 依頼とモデル呼び出し・
トークン・再計画予算を維持し、既存 Project キュー経由で一度だけ再開します。
外部返信は承認や権限の根拠にはなりません。追加質問には同じ Run の完了済み依頼 ID が
必要で、既定は総数3件・追加2ターン・委譲深度1です。同じ質問の繰り返しを拒否します。
タイムアウトは1～設定値秒（設定上限3600秒）、質問2048文字、期待出力512文字です。

## Phase 2：状態・復旧・動作確認

`storage.db` の `slack_agent_*` テーブルに依頼、返信、Dependency 対応、再開履歴、
チャンネル単位の Retry-After を保存します。既存の共有 SQLite / トランザクションを
使う追加テーブルです。グローバルの保存形式バージョンを変更しません。
別ブランチで作った新しいスキーマのデータを古い main に渡す問題は解消しません。
その場合は対応するアプリを使うか、`REI_DATA_DIR` を分け、Project と Session を登録してください。

| 状態 | 意味・復旧 |
| --- | --- |
| CREATED | 権限確認・保存済み、未送信。再起動後も処理可能 |
| SENDING | 送信前に所有を記録。再起動時は UNKNOWN、再投稿しない |
| WAITING | 投稿 TS 確認済み。期限まで返信収集 |
| COMPLETED | 管理者設定の最終回答専用 prefix を確認 |
| FAILED | Slack が明確に送信を拒否 |
| TIMED_OUT | 期限超過。部分回答は保持、完了扱いしない |
| CANCELLED | 親 Run / Dependency の取消。遅延返信で再開しない |
| UNKNOWN | 送信・再開の結果不明。人間が台帳・Slack・チェックポイントを確認 |

429 は保存した Retry-After に従います。同一チャンネルは最大1回/秒を目安とし、
再起動後も別の依頼による制限回避を防ぎます。タイムアウト・不明応答は再送しません。
送信中にキャンセルしても、Slack がすでに受理した投稿を撤回できません。判明した
投稿 TS は取消済み台帳に保存します。Slack 側の exactly-once は保証しません。

通常の人間 Run の保存済み WAITING は、同じ Backend が保存済み予算を使って復旧します。
再開を投入済みの `RESUMING` の所有を失った場合は UNKNOWN とし、自動再実行しません。
Goal / Scheduler の親を持つ待機は、同一プロセス内で既存の予約と完了通知を維持します。
再起動でその親を失った場合は UNKNOWN にして、既存 Goal / Timer / チェックポイントの
明示的な復旧に委ねます。親予算の再作成や自動再送は行いません。返信台帳は保持します。
設定から Bot を削除・変更した場合も、古い宛先へ送り直しません。

`externalAgentStatus(requestId)` は同じ Project / Session の保存済み結果を読むだけです。
Socket の受信時に、Bot ID・`bot_message` subtype・登録済み Bot User ID・自 Bot User IDで
送信元を分類してから、人間向け受付処理へ振り分けます。Bot の返信は外部依頼の
Workspace / Channel / Bot ID / Bot User ID / Thread TS / 待機状態を照合して保存します。
Bot ID が欠ける登録済み Bot のイベント、未許可 Bot、終了後や別スレッドの返信は
人間向け受付へ戻さず無視します。Bot 返信から新規 Run や受付通知を生成しません。
本文の「受付しました」という文字列は判定に使いません。人間の通常依頼には従来どおり
`受付しました。Run: {runId}` を通知します。
`rei status` / `rei cancel` は Phase 1 と同じ人間の元スレッドで使えます。
待機中は元スレッドへ外部返信待ちを通知します。てんてんの返信スレッドを人間の
会話スレッドとして登録しないでください。

実環境の最初の試験では `timeout-seconds: 30`、`max-requests-per-run: 1`、
`max-follow-up-turns: 0` とし、機密を含まない短い挨拶を指定してください。
元の人間 Run の WAITING、てんてんの返信の実際の Bot ID / User ID / Thread TS、
元 Run の再開、受付と最終回答の区別を確認してから制限を増やしてください。
今回の開発プロセスでは Slack トークンが利用できず、実てんてん E2E は未実施です。
Phase 1 のユーザーによる動作確認は、Phase 2 の Bot 間返信・完了形式の確認を兼ねません。

仕様の根拠：
[bot_message（Bot ID と classic Bot の User 不在）](https://docs.slack.dev/reference/events/message/bot_message/)、
[message](https://docs.slack.dev/reference/events/message/)、
[chat.postMessage](https://docs.slack.dev/reference/methods/chat.postMessage/)、
[メンション書式](https://docs.slack.dev/messaging/formatting-message-text/)。
