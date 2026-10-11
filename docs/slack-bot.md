# Slack Bot（Phase 1）

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
Bot 間自動会話は無効です。次 Phase では Bot の個別認可、ループ・予算制御、
委譲の provenance、結果と依頼の区別を別途設計する必要があります。

## 検証

単体・統合テストは Slack トークンを使わず、SDK の MethodsClient と接続を Fake に置き換えます。
実 Workspace での Socket 接続・Scope・実際のメッセージ往復は未検証です。
運用開始時は最小の許可リストで DM／メンション／スレッド継続／再起動／承認／キャンセルを確認してください。
