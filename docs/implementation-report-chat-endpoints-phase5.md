# 通常チャットの共通接続基盤 Phase 5

## 作業状態

Phase 4 のマージコミット `4ee90a5a12afbfa2a655132b616498601836f5bb` を基点に実装。
OAuth・利用枠・要求単位の同時実行制御を、通常チャットの共通接続基盤と既存の委譲経路へ接続した。
実装とローカル検証を完了。コードレビューで見つかった登録をまたぐ失効通知と、受付後のストア障害時の枠解放も修正し、最新コードの全回帰を再実行して成功した。
PR の CI・マージ・マージ後検証は作業記録で別途追跡する。
実アカウント・実モデルへの推論、実 Codex app-server の起動は実施していない。

Plan の通常チャット用 transport capability は、この Phase では未確認のまま維持する。
Phase 6 の外部送信許可、namespace、ツール所有権、3実行モードの統合前に、通常チャットの Plan 経路を有効化しない。
fixture の成功を実サービスでの対応確認として扱わない。

## 共通基盤への組み込み

`ChatPlanAccounts` を、既存の OAuth・保護ストアを所有する `PlanClient` が実装する。
`ChatConnectionService` はこのポートからローカルの登録状態、選択アカウントのモデル一覧、遅延資格情報、要求枠を取得する。
通常チャット用の OAuth ループや別のエージェント実行エンジンは追加しない。
既存の補助機能の接続先は変更しない。

キャッシュの生成時に、受付済みの immutable `RunLlmConnection` をデコレーターへ渡す。
OAuth モデルの `PlanRequestChatModel` は、既存のイベント発行・障害受付デコレーターの内側に置く。
そのため、障害からの復旧待ちでアカウントの要求枠を占有しない。
キャッシュから追い出されたモデルを実行中 Run が保持していても、別の選択先やアカウントを取り直さない。

## 登録・認証・モデル一覧

- `TokenSet.registrationId` はランダムな登録識別子で、トークン値のハッシュではない。refresh は同じ値を保持し、再サインインは新しい値になる。
- 既存の保存データに識別子がない場合、既存の JVM・OS ロック内で暗号化保存を更新してから値を公開する。
- token clear / logout は登録と発行済み client の対応を保持する。資格情報を Run、Checkpoint、診断へ保存しない。
- `PlanOAuth.access(account, registration)` は既存の refresh ロックを再利用し、別の登録への切り替わりを拒否する。
- refresh 後は新しい scope を再検証する。同意不足ではサインイン情報を保持して推論を停止する。
- モデル一覧のキャッシュは account / client / subject / registration を照合し、取得途中の logout・再登録でも古い一覧を公開しない。
- 共通の `/model` は Plan の `models` / `slug` 形式と表示順を使う。SDK の標準モデル一覧形式や推論用生成設定を流用しない。
- OAuth の SDK 入口は named Plan / Responses / 固定 public resource / account / registration / credential reference の一致を確認する。ローカルや API キーの接続へ OAuth を流さない。

None 認証ではアカウントポートを呼ばない。既存の API キー・None 接続の資格情報分離を維持する。

## 共有する要求枠

`rei.chatgpt-plan.max-concurrent` は、1つのプロセス内の account / registration ごとの要求上限。
既定値は `1`、許容範囲は `1..8`。従来の3引数設定コンストラクターも既定値1を維持する。
既存の委譲ワークフロー全体の実行数制限とは別の設定である。

| 経路 | 枠を保持する単位 |
|---|---|
| 通常チャットの共通モデル | 1回のモデル stream。同期 call も同じ stream と既存 MessageAggregator を使う |
| 既存の直接 Responses 委譲 | 遅延資格情報取得・dispatch から応答の完了または失敗まで |
| 既存 Codex app-server 委譲 | 起動前から観測可能な外部セッションの終了・結果検証まで |

app-server 内部の HTTP 要求は観測できない。その内部要求ごとの制御ができるとは報告しない。
通常チャットを app-server に委譲する変更も行わない。

要求枠は成功・失敗・キャンセルで一度だけ解放し、通常の完了で失効シグナルを発火させない。
完了・失敗を下流へ通知する前に枠を返すため、親の推論終了後に子の要求を開始できる。
満杯の場合は HTTP dispatch 前に BUSY を返し、Run 全体やツール待ちで枠を保持しない。
空になった登録の要求スコープは削除する。

## logout・取消・登録変更

要求の失効理由は STOPPED / SIGNED_OUT / REGISTRATION_CHANGED / AUTHENTICATION_INVALID / CONSENT_REQUIRED の5種類。
最初の理由を保持し、登録変更や logout の通知で上流ストリームをキャンセルしてから安全な診断を返す。

logout 進行中は新しい資格情報取得や要求受付を拒否する。
同時に走っていた refresh はローテーションされた refresh token を保存し、logout がその値を revoke に使えるようにする一方、取り消し中の呼び出し元へ資格情報を返さない。
一時的な通信障害、古い Run の登録不一致、失敗した再ログインだけでは、新しい登録の資格情報や要求を無効化しない。
通知は既存ストアのロックを解放した後に行い、同期的な要求解放・再受付との競合を避ける。

コードレビューでは、ロック解放と通知の間に新登録の要求が受付されると、遅れた古い通知が新登録も停止する競合を確認した。
再登録・認証失効・scope 不足の3ケースを latch で再現し、いずれも新登録の future が誤って完了する assertion failure を確認した。
失効した登録をストアのトランザクション内で捕捉し、その登録の要求だけへ通知する修正を追加した。
利用者の stop / logout は引き続きアカウント全体へ適用する。
要求受付終了時の登録再確認と、直接推論の要求失効シグナルも検証し、対象を狭めることで古い要求の取消を失わないようにする。
登録の最終再確認でストアが読み取れなかった場合も、すでに取得した枠を返して元の安全なエラーを伝える。
復旧後の要求が BUSY のまま残らないことを、保護ストアの読み取り障害を注入したテストで確認した。

## 利用枠の停止と明示再受付

推論応答の正確な `subscription_sharing_usage_limit_exceeded` を確認した場合だけ、そのアプリのアカウント登録に新規要求の停止を記録する。
HTTP、SSE error、delta 後の error、response.failed の経路を検証する。
一般的な HTTP 429、モデル一覧取得の失敗、ローカルに作った USAGE_LIMIT 診断から、この根拠を作らない。
別のアカウントや新しい登録へ停止状態を引き継がず、ChatGPT 全体の残量やリセット時刻は推測しない。

停止の公開と要求受付は同じローカルの compute 操作で直列化する。
すでに dispatch された別要求を、利用枠通知だけで取り消す変更は行わない。
停止状態はプロセス内の状態であり、リモートの残量を表すキャッシュではない。

接続先の一覧・詳細には停止理由を表示し、ログイン済みという状態とは分ける。
利用者は ChatGPT 設定の [Usage](https://chatgpt.com/settings/usage) を確認した後、次を実行できる。

```text
/chatgpt resume --account personal --confirm-usage
```

account と確認フラグは必須で、明示的な false は拒否する。
この操作はローカルの受付停止を解除するだけで、推論送信、refresh、再ログイン、資格情報の変更は行わない。
解除によって残量の確認済み状態を作らず、次の要求に対するサーバーの判定に従う。
`subscription_sharing_usage_unavailable` は資格情報を保持する。別接続・別モデル・別アカウントへ自動フォールバックしない。

## 安全な診断と Usage

`ChatGptException` は既存の `ExternalReadFailure` 契約へ安全なコードと案内を渡す。
SDK のラップがあっても、共通の Responses 境界はその情報だけをコピーし、元の例外・応答本文・資格情報を保持しない。
Plan の要求は、HTTP ステータスが一般的な一時障害であっても、不明な実行結果を自動再送する許可を出さない。
既存の API キー・ローカル接続の再試行分類は変更しない。

失敗時に確定した Usage は保持する。欠落時の既存の `ModelTokenUsage` は各値 null のままで、0へ変換しない。
リクエスト・レスポンスのバイト上限と、Run の呼び出し回数・トークン予算を混同しない。

## 検証

検証対象コードは `5bfb120256cea820639d2931d7162f07ce88e225`。この後の実装報告の追記は文書のみ。

| 検証 | 確認済み結果 |
|---|---|
| 関連回帰（既定の通常コンパイル、2026-11-26 20:17:03 JST） | 560件、failure / error / skip = 0。アーキテクチャ検査6件を含む |
| terminal verify / 配布ビルド（19:29:02 JST） | 成功。36 suite / 205 test、failure / error / skip = 0 |
| 最新コードの静的解析（20:20:03 JST） | Checkstyle 0 / SpotBugs 0 / analysis errors 0 / Spotless 3286 clean。既存 CI ゲート成功 |
| 最新の検証対象コード | `5bfb120256cea820639d2931d7162f07ce88e225`。本体1800ファイル・テスト1486ファイルを通常コンパイル済み |
| 全回帰（20:40:51 JST） | full プロファイルをフィルターなしで実行。8931件中8919件成功、既存 skip 12件、failure / error = 0。所要18分59秒 |
| 通知競合の再現 | 24件中、新規3ケースだけが assertion failure。既存21件成功、error / skip = 0 |
| ストア障害後の枠漏れの再現 | 17件中、新規1ケースだけが assertion failure。既存16件成功、error / skip = 0 |

各機能で assertion による Red を確認してから実装をコミットした。
通常コンパイルに失敗した新規テストの型指定や fixture のモデル名不一致は、新規テスト内だけで修正した。
既存の期待値削除・緩和、skip 追加、静的解析の抑制、アーキテクチャの予算・分類・ゲート変更は行わない。
静的解析で確認した1件の不要な再ボックス化は、既定値を代入する null 分岐に変更して解消した。

使用した実 SDK は OpenAI Java 4.101.0 と Spring AI 2.1。
HTTP/SSE は所有する localhost fixture または SDK interceptor の応答で検証し、実サービス適合・実モデル対応の証拠にはしない。

## 次 Phase との境界

Phase 6 で外部送信許可とツール namespace / searchTools / 3実行モードを統合する。
Phase 7 で承認・Checkpoint・再起動後の復旧・不完全な応答・副作用の再実行防止を検証する。
履歴投影・メディア・GUI・設定例と互換性マトリクスは Phase 8〜10 で扱う。
この報告だけで全 Phase の完了とはしない。

## 公式資料

確認日: 2026-11-26。仕様確認は実接続試験ではない。

- [Models and inference](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference)
- [Errors and recovery](https://developers.openai.com/siwc/token-sharing-open-source/errors-and-recovery)
- [Profiles and sessions](https://developers.openai.com/siwc/token-sharing-open-source/profiles-and-sessions)
- [Preview limitations](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations)
