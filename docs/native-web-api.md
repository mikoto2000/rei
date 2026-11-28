# Native Client と Web API v1

SSEの各イベント配信では対象Runの状態だけを照会し、全Run・再送履歴の期限切れ掃除や
Run ID一覧の取得は繰り返しません。対象Runの取消状態は既存の永続照会で確認し、
終端から30分の保持期限も従来どおり判定します。通常のRun照会では全体の掃除を行います。

## 起動と接続

サーバーの設定・登録済みプロジェクトを準備し、リポジトリのルートで起動します。

```powershell
$env:REI_API_KEY = '<設定する API key>'
.\mvnw.cmd spring-boot:run
```

既定の bind は `127.0.0.1`、port は `8080` です。LAN や Tailscale の別端末から接続する場合は、クライアントが信頼する証明書を使う HTTPS リバースプロキシを経由してください。同じ PC にプロキシを置く場合、Rei は既定の `127.0.0.1:8080` で待ち受けられます。プロキシでは SSE のレスポンスをバッファリングせずに転送します。

Native Client は `client` で `npm run tauri -- dev` を実行します。Settings で Credential Vault を解錠し、Server の Base URL と API Key を保存します。URL は API prefix を含まないサーバーの URL です。

| 接続場所 | Base URL の例 |
| --- | --- |
| 同じ PC | `http://127.0.0.1:8080` |
| LAN（HTTPS プロキシ経由） | `https://rei.example.net` |
| Tailscale（HTTPS 終端を設定済み） | `https://rei-host.example.ts.net` |

API Key は既存の暗号化 Vault に保存され、Rust が `Authorization: Bearer …` ヘッダーを設定します。接続テストは、認証不要の health と認証必須の projects を別々に確認します。health 成功だけでは認証済みと表示しません。

HTTP では Bearer キーが平文で送られます。HTTP を使う場合は、ループバックか、別端末までの全区間が暗号化されたトンネル内に限定してください。

## 会話と Run

新しい会話では登録済み projectId を選択します。最初の送信は新規 Session、以降は同じ Session へ送信します。Conversations はサーバーの Sessions / Turns API を利用し、タイトル・project・日時・回答履歴を表示します。過去の Shell 履歴は移行しません。

SSE は Rust の共通 RunManager が処理します。再接続は既存の bounded backoff（1, 2, 4, 8, 15, 30 秒）で行い、最後に検証して受理した event id を `Last-Event-ID` で送ります。heartbeat は cursor を進めません。ReplayBuffer の `409` は履歴欠落として区別し、SSE の再試行を止めて Run 状態を取得します。欠落したテキストは再構築せず、不完全な表示であることを示します。

Run の状態はサーバーと同じ QUEUED / RUNNING / COMPLETED / FAILED / CANCELLED です。Stop の `202` は「キャンセル要求済み」と表示し、SSE または状態更新で terminal を確定します。イベントはライブのメモリ内だけに保持し、永続化しません。

## 通常チャットの接続先とモデル

会話画面では、サーバーに登録した接続先とそのモデルを選択できる。
OpenAI API・ChatGPTサブスク・ローカルLLMは同じ選択サービスを使用する。
設定とCLIの操作は[共通接続ガイド](chat-llm-connections.md)を参照。
OAuthログインはサーバー側の既存コマンドで行う。

選択はサーバープロセス全体の通常チャットに適用され、次に受け付けるRunから有効になる。
進行中・承認待ち・Checkpointの接続は変更しない。GUI再起動時は保存されたサーバーを
識別して現在値を読み直し、サーバー再起動時は設定のactiveから開始する。

接続先一覧・現在値の取得だけでモデルサービスへ通信しない。モデル一覧の明示取得は
選択先のサービスを照会する。モデル変更は接続先の同一性を確認し、別クライアントの
切替と競合した場合は409として再取得する。既存のモデル名だけの変更要求も保持する。
GUIでは表示・更新中の状態を検査し、古い一覧や応答による上書き、送信との競合を防ぐ。

## 自発投稿案

「投稿案」は接続先サーバー全体の機能で、Project/Session の選択は不要です。
公開用の話題を登録して現行版の生成利用を許可すると、手動または定期的にドラフトを生成できます。
生成・通知確認では公開しません。詳細で最新の全文・投稿先 DID・改訂番号を確認し、
「この全文を公開」を明示的に選ぶと既存の Bluesky 送信設定・権限で公開します。
編集・再生成の後は新しい全文を確認します。通信切断時に公開操作を自動で再送しません。

| 操作 | 認証付き固定 API |
| --- | --- |
| 一覧・詳細 | `GET /api/v1/autonomous/drafts`、`GET /api/v1/autonomous/drafts/{id}` |
| 手動生成 | `POST /api/v1/autonomous/drafts/generate`、本文 `{}` |
| 編集・再生成・見送り | `POST /api/v1/autonomous/drafts/{id}/edit`・`/regenerate`・`/reject`。確認した `expectedRevision` が必須 |
| 明示公開・読取照合 | `POST /api/v1/autonomous/drafts/{id}/publish`・`/reconcile`、本文 `{"expectedRevision":3}` |
| 確認待ち通知 | `GET /api/v1/autonomous/notifications`、`POST /api/v1/autonomous/notifications/{id}/ack`（本文 `{}`、承認ではない） |
| 公開・生成履歴 | `GET /api/v1/autonomous/history/published`・`/generation` |
| 公開用話題 | `GET`・`POST /api/v1/autonomous/sources`、詳細 `GET /sources/{id}`、編集・許可・取消 `POST /sources/{id}/edit`・`/permit`・`/revoke`（いずれも prefix は `/api/v1/autonomous`） |

一覧の `limit` は既定 20、1～200 件です。未知の更新項目、古い版、任意の actor・承認フラグを拒否します。
話題の編集は必ず利用許可を外します。見送り理由のコピーも未許可の入力から始め、
非公開部分を取り除いた別資料の現行版を明示許可します。
`UNKNOWN` は再送せず「公開結果を照合」で固定キーを GET し、全内容の一致を確認します。
履歴表示は外部投稿の現在の存在確認ではなく、保存済みの確認結果です。
サーバー切り替え時は旧本文・入力を消し、古い応答を破棄します。

機能は既定で無効です。API キーを持つ任意プログラムを人間と区別する新しい認証方式は追加していません。
[設定・全操作・安全性の範囲](autonomous-action.md) と [検証・拡張方針](autonomous-action-verification.md) を参照してください。

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
