# Native Client と Web API v1

## 起動と接続

サーバーの設定・登録済みプロジェクトを準備し、リポジトリのルートで起動します。

```powershell
$env:REI_API_KEY = '<設定する API key>'
.\mvnw.cmd spring-boot:run
```

既定の bind は `127.0.0.1`、port は `8080` です。LAN / Tailscale の別端末から接続するときは、サーバーの `rei.web.bind-address` を接続可能なインターフェースへ設定します。

```powershell
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.arguments=--rei.web.bind-address=0.0.0.0 --rei.web.port=8080'
```

Native Client は `client` で `npm run tauri -- dev` を実行します。Settings で Credential Vault を解錠し、Server の Base URL と API Key を保存します。URL は API prefix を含まないサーバーの URL です。

| 接続場所 | Base URL の例 |
| --- | --- |
| 同じ PC | `http://127.0.0.1:8080` |
| LAN | `http://192.168.1.20:8080` |
| Tailscale | `http://rei-host.example.ts.net:8080` |

API Key は既存の暗号化 Vault に保存され、Rust が `Authorization: Bearer …` ヘッダーを設定します。接続テストは、認証不要の health と認証必須の projects を別々に確認します。health 成功だけでは認証済みと表示しません。

## 会話と Run

新しい会話では登録済み projectId を選択します。最初の送信は新規 Session、以降は同じ Session へ送信します。Conversations はサーバーの Sessions / Turns API を利用し、タイトル・project・日時・回答履歴を表示します。過去の Shell 履歴は移行しません。

SSE は Rust の共通 RunManager が処理します。再接続は既存の bounded backoff（1, 2, 4, 8, 15, 30 秒）で行い、最後に検証して受理した event id を `Last-Event-ID` で送ります。heartbeat は cursor を進めません。ReplayBuffer の `409` は履歴欠落として区別し、SSE の再試行を止めて Run 状態を取得します。欠落したテキストは再構築せず、不完全な表示であることを示します。

Run の状態はサーバーと同じ QUEUED / RUNNING / COMPLETED / FAILED / CANCELLED です。Stop の `202` は「キャンセル要求済み」と表示し、SSE または状態更新で terminal を確定します。イベントはライブのメモリ内だけに保持し、永続化しません。

## Workspace

既存 UI に追加した Workspace パネルで操作を選択し、専用フォームから実行します。

| 機能 | 利用可能な操作 |
| --- | --- |
| Search | query / Vector 件数 / Web 件数 / threshold による検索 |
| Briefing | 当日の briefing 取得 |
| Feed | 一覧・詳細・追加・displayName/enabled 更新・削除 |
| Profile | イベント統計の取得 |
| Skills | 一覧・詳細・設定済み repository の再読込 |
| Summaries | 登録済み project を選択して URL 要約 Run を開始 |
| Images | 登録済み project を選択して prompt / optional size による生成 Run を開始 |
| Reminders | 有効な通知の一覧・詳細・日時指定 / 対象時刻の何分前指定による作成・削除 |
| Interests | 過去 hours の一覧・topic/reason/query/summary/source URLs の保存 |
| Memories | 一覧・詳細・type/scope/confidence を指定した作成・論理削除 |

Summaries / Images は chat と同じ RunManager・SSE・cancel を利用します。sessionId / turnId は `null` で、会話を作成しません。Workspace では進行中のイベントを既存の RunTimeline で表示し、終了後は結果テキストを表示します。Active Runs から Workspace の Run を開けます。

## サーバー契約による制限

- 画像の配信・ダウンロード API はありません。結果に表示される保存先はサーバー側の相対パスです。クライアントから任意の path を送信・取得しません。
- Memory の PROJECT / SESSION scope は、既存モデルに所有情報がないためサーバーが拒否しています。クライアントは GLOBAL / SHORT_TERM / LONG_TERM / PERMANENT のみ選択できます。
- Profile 更新、Skill ファイルの作成・変更・削除、Reminder 編集 / done、Interest 編集・削除、Memory 内容編集は API がないため操作を用意していません。
- OAuth、Shell 実行、project の登録・削除、外部投稿等の非公開 operation は追加していません。
- クライアントを再起動した場合、終了済み background Run の結果一覧は復元しません。永続 Session のチャット履歴は取得できます。
- background Run の最終結果を永続取得する endpoint もありません。ReplayBuffer が欠落した場合、状態を確認できても失われた結果テキストは復元できません。

## 構造と TDD

React → 型付き Tauri command → Application → 既存 ReiClient / HttpReiClient → HTTP / SSE の経路を維持しました。追加した WorkspaceOperation / BackgroundOperation は operation の明示的 allowlist です。自由な endpoint、HTTP method、filesystem path は入力に含みません。

API adapter は専用のサーバー DTO をデコードし、UI 向け WorkspaceResult / WorkspaceItem に変換します。read / write / background は Rust の別モジュールに分けました。既存の Vault、Session サービス、Projection、RunManager、イベント表示を共有します。Web API 本体の変更はありません。

以下の各 slice でテストを先に追加して Red を確認し、実装後に Green と関連 regression を確認しました。ログは ignored な `target/native-*-red.log` / `*-green.log` に保存しています。

| Slice | Red の内容 | Green |
| --- | --- | --- |
| Read API | WorkspaceOperation / workspace が未実装 | 認証済み read adapter、DTO 変換、検索 body |
| Write API | write operation が未定義 | 明示的 CRUD、未知フィールド拒否、所有不明 scope 拒否 |
| Background | API / Projection が未実装 | runId のみの receipt、Location、null ownership |
| Cancel | cancelRequested がない | 202 と terminal を分離 |
| Workspace UI | コンポーネントがない | read / write / summary form と command 呼出 |
| Navigation | Workspace 導線がない | 既存 nav と Tauri command 登録 |
| Chat cancel UI | Stop のまま | 要求済み表示と二重操作の抑制 |
| Reminder contract | 0分前をクライアントが拒否 | サーバー既存制約に合わせる |
| HTTP contract | write 200 / read 201 / 非 JSON を受理 | method ごとの status / Content-Type 検証 |
| Background metadata | 空文字を UI へ送出 | UI の sessionId / turnId を null とする |
| Active Runs | background 導線と要求済み表示がない | Workspace を開く、要求済み cancel を無効化 |
| Integer input | 小数の ID / 件数を受理 | 非有限値・小数の整数フィールドを送信前に拒否 |

非 chat Run の再接続・heartbeat・replay gap・terminal 後の購読停止と、各 read API の 400 / 401 / 404 / 409 / 5xx も契約テストで検証しています。
