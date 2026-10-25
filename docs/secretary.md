# 秘書機能

## 実装順序と既存機構

Phase 1 → 2 → 3 → 5 → 4 の順で独立した PR とする。後続 Phase は前段のマージ後に開始する。
開始時の API・保存状態・既存資料との差異は [既存実装調査](secretary-investigation.md) に記録する。

| 既存機構 | 責務・統合方針 |
| --- | --- |
| `task.TaskService` / `/task` | Google Tasks。ローカルの約束台帳とは独立し既存の認証・動作を保つ |
| `application.task` / `/api/v1/tasks` | Run・Checkpoint・Goal の実行状態の投影。約束が存在しても Run は開始しない |
| `PersistentAgentScheduler` | 時刻・cron・イベントによる永続的な実行予約。Phase 2 の周期起動に利用する |
| Planning Loop / SubAgent / Codex・Claude 委譲 | 複雑な実行を担う。秘書の提案を実行権限とみなさない |
| `PersistentDependencyRepository` | 外部条件の観測。待機タスクと参照で結ぶ |
| `ToolPermissionGuard` / Approval | ツールごとの権限と承認。自律性レベルで緩和しない |
| Activity Timeline / Desktop Monitoring | 観測された作業情報。無効・未観測は不明として扱う |
| Memory / Episode / Hybrid RAG | 長期記憶・検索。タスク台帳の状態とは分離する |
| Auto Sleep | 記憶整理。新規入力の抽出に毎回の全文再処理や睡眠を要求しない |
| SessionLifecycle / Conversation History | 新たに受理した入力を `ConversationAccepted` として通知。過去全文は渡さない |
| Slack Bot / MCP | 既存の受付・権限境界を維持。外部情報を命令として自動実行しない |
| 起動時再開候補 / Run 完了検証 | 既存 Checkpoint の継続管理。約束台帳の DONE と Run 成功を混同しない |
| StorageMigrationCoordinator | 起動前のバックアップ・プロセス利用権・トランザクション移行。新規台帳にも共用 |
| Attention / 通知配信 | 永続受信箱・通知履歴。Phase 2 以降で連携する |

## Phase 1: 約束・タスク台帳

`SecretaryLedger` は `SecretaryDatabase` port と `SqliteSecretaryDatabase` adapter を介し、
既存 `storage.db` のタスク・出典・更新履歴・入力重複防止を同じトランザクションで更新する。
秘書のドメインは storage・CLI 登録側・会話受付側の実装に依存せず、既存の循環依存予算を拡大しない。
schema 10 → 11 は既存の自動バックアップ付き移行に参加する追加変更で、既存データを削除しない。
機能 OFF でも起動前移行は行われるが秘書の受付や処理は開始しない。

意図は IDEA / TODO / COMMITMENT、進行状態は CANDIDATE / OPEN / WAITING / DONE / CANCELLED。
会話抽出は意図が明確でも CANDIDATE。approve の明示操作で OPEN とする。
IDEA も承認できるが、承認は作業実行の許可ではない。DONE / CANCELLED は終端で編集不可。
更新には最新 `revision` が必要。期限は日付で、更新時に null を渡せば削除する。
優先度は 1（最高）〜5。タグと Run / Plan / PR / Dependency 等への参照を保持する。

受付フロー: SessionLifecycle → ConversationAccepted → SecretaryIntake → SecretaryLedger。
受付済みメッセージ一件だけを上限 32,768 文字、候補 16 件以内でルール評価する。LLM 呼び出しはゼロ。
`したい`、`します`、`TODO` 等の限定した表現を抽出するため、全ての自然言語表現に対応するわけではない。
否定表現・スラッシュコマンドは除外する。抽出失敗は通常の会話実行を妨げず、本文をログに出さない。
長文は観測入口で上限までとし、手動 extract API は上限超過を拒否する。

出典キーの再送は同じタスクを返す。異なる本文で同じキーを利用すると拒否する。
同じ正規化タイトルの抽出候補には出典だけを追加し、明示登録のメタデータを上書きしない。
`related` は最大 1,000 件から最大 20 件を返す補助情報で、自動統合はしない。
文字 bigram 類似度に加え、共有タグと、旅行・調査・会議・要約・返信・試験・公開・レビューの
限定した日英の同義概念で意味的な関係を示す。一般的な意味理解を保証する方式ではない。
タスク名を外部 embedding 接続先へ送信する実装は含めていない。
大量台帳ではページを使い、類似候補の探索範囲に注意する。
台帳一覧は project・priority・id で安定順序、最大 1,000 件/ページ。履歴・出典は先頭 1,000 件まで返す。
全ての変更履歴と出典は DB 内には削除せず保持する。

### 有効化

既存 application.yaml に設定する。デフォルトはいずれも OFF。

```yaml
rei:
  secretary:
    enabled: true
    extraction-enabled: true
```

外部サービスの認証は不要。記録は既存の `rei.data-dir` 配下。
タスク・出典・履歴は入力数と更新回数に比例して増える。履歴を LLM プロンプトへ自動追加しない。

### CLI

`PROJECT` は既存プロジェクト ID。スラッシュ入力は通常の Shell コマンドとして使用する。
軽量版 `rei-cli` からも同じ `/secretary` を利用でき、`--project` を省略した場合は選択中 Project を使う。
軽量版の `--tag` と `--ref` はカンマ区切りで指定する。誤った操作・未知の引数をモデルへ送らない。

```text
/secretary extract --project PROJECT --key conversation:example --title "来週 Phase 2 を進めたい"
/secretary list --project PROJECT --offset 0 --limit 100
/secretary show TASK_ID --project PROJECT
/secretary sources TASK_ID --project PROJECT
/secretary related TASK_ID --project PROJECT
/secretary approve TASK_ID --project PROJECT --revision 0
/secretary add --project PROJECT --key manual:release --title "リリース準備" --intent COMMITMENT --due 2026-10-30 --priority 1 --tag release --ref pr:123
/secretary update TASK_ID --project PROJECT --revision 1 --title "リリース準備" --status WAITING --due 2026-10-31 --priority 2 --ref dependency:ID
/secretary done TASK_ID --project PROJECT --revision 2
/secretary history TASK_ID --project PROJECT
/secretary cancel TASK_ID --project PROJECT --revision 0
```

update は全メタデータの置換であり、省略したタグ・参照・期限はクリアされる。事前に show で確認する。
API/CLI に同じ key と同じ内容を再送することは可能だが、異なる内容の登録には新しい key を指定する。

### API

既存の API キー認証下で `rei.web.enabled` と秘書を有効にする。
`/api/v1/secretary/tasks` に GET（projectId, offset, limit）と POST。
`/{id}` に GET と PUT。`/{id}/sources`, `/{id}/history`, `/{id}/related` に GET（projectId 必須）。
`/extract` に POST (`projectId`, `eventId`, `text`)。
`/{id}/transition` に POST (`projectId`, `revision`, `status`)。
POST/PUT の本文は `projectId`, `key`（POST）, `title`, `intent`, `status`（PUT）,
`due`（ISO 日付または null）, `priority`, `tags`（配列）, `references`（配列）, `revision`（PUT）。
MCP ツールはまだ公開しない。LLM が候補の承認を代理で実行する経路を追加しない。

### 検証と障害診断

```powershell
.\mvnw.cmd -B -Pfull '-Dtest=SecretaryLedgerTest,SecretarySurfaceTest,SecretaryMigrationTest,ShellSessionLifecycleTest' test
.\mvnw.cmd -B -Pintegration verify
.\mvnw.cmd -B checkstyle:checkstyle
.\mvnw.cmd -B -DskipTests compile spotbugs:spotbugs
.\.github\ci\static-analysis-gate.ps1
.\mvnw.cmd -B -f terminal/pom.xml verify
```

競合時は最新 revision を取得して更新する。移行失敗時は既存の storage 起動停止とバックアップに従う。
旧バージョンへの移行 fixture は現行 DB から後発テーブルを除去して旧形式を再現する。
schema 11 追加に伴い、その除去対象にも秘書の 4 テーブルを追加する。既存の試験条件・期待結果は変更しない。
抽出されない表現は明示登録か手動 extract で確認する。未設定での外部接続は発生しない。
Phase 1 自体には監視・提案・自律実行・外部サービス統合を含めない。監視と報告は以下の Phase 2 を参照する。

### Phase 1 の検証記録

- 台帳6件、CLI/API/設定2件、HTTP競合1件、ローカル関連付け2件、schema 10→11 の移行1件、
  実HTTP認証・会話受付・再起動1件の計13件を追加。外部サービスや実 LLM は使用していない。
- 実装前の台帳テスト、HTTPでの古い revision、関連付けの各 Red を確認して実装した。
  循環依存が増える初案は採用せず port/adapter に分離し、既存の依存許容基準のままで通過した。
- ローカルの全体回帰は 6,294 件、failure 11 / error 0 / skip 5 だった。
  11 件は既存 SubAgent の「例外に secret がないこと」の検査が、今回の一時パス中の
  `secretary-phase1` に一致したもの。検査条件を緩めず、一時パスからその文字列を除いた。
- 別の一時ディレクトリで失敗した SubAgent 50 件・新規13件・構造検査6件を再実行した
  `-Pintegration -Dtest=SubAgentResultValidatorTest,Secretary*Test,PackageArchitectureTest,ArchitectureGuardTest verify`
  は69件、failure/error/skip 0で成功し、実行可能 JAR も生成した。
  ローカルの全件実行そのものが成功したという記録とは区別する。全件の最終判定は PR CI でも確認する。
- 初回のサンドボックス内 HTTP 試験は loopback 接続を拒否された。接続可能な環境で再実行し成功した。
- Checkstyle は0件。SpotBugs は既存1,897件、新規0件。既存CIの増分ゲートと、その自己試験8ケースが成功。
  静的解析の許容基準・検査除外は変更していない。
- 軽量CLIには3件の Red を確認して固定 API への接続を追加した。通常会話へのフォールバックを行わない。

一般的な embedding モデルによる意味検索は含めない。語彙範囲が明示されたローカル関連付けのみであり、
タスク名・会話を外部モデルへ送信する経路は追加していない。

## Phase 2: フォローアップ・朝夕の報告

Phase 1 は [PR #150](https://github.com/mikoto2000/rei/pull/150) / merge `5153f3ba` で main に取り込まれた。
最終のローカル全件 `-Pintegration verify` は6,294件、failure/error 0、既存 skip 5、JAR 生成成功。
GitHub の全体回帰・静的解析も成功してからマージし、Phase 1 の worktree とローカルブランチを削除した。

### 責務とイベントフロー

- `SecretaryFollowups`: 期限・放置・待機の確認、朝夕報告、通知キューと履歴。通常判定に LLM は使わない。
- `AgentScheduleDispatcher`: 既存の定期起動から秘書の軽量 tick を呼ぶ。エージェントの予約実行を有効化せずに利用可能。
  秘書側が失敗しても既存のエージェント dispatch を止めない。
- 秘書ワーカーは1本・有界 queue 1件。実行中の tick はまとめ、終了時には割り込みと最大5秒の終了待ちを行う。
- `SecretaryDependencyIntake`: 既存の所有 Project 付き依存状態イベントだけを受け取る。
  `PersistentDependencyRepository` の最新の保存事実も読み、再起動やイベント欠落後も確認できる。
  秘書側で新たに外部 API をポーリングしない。ネットワーク再試行は既存の Dependency watcher が担当する。
- `SecretaryContext`: 既存 Scheduler の予定と、有効な `DailyPlanningService` の現在の内部作業投影を読み取る窓口。
  プロバイダー未設定・失敗時は他の情報で報告し、部分結果を示す。
- `AttentionService` / `AttentionRepository`: 同じ通知 ID の再送を同じローカル受信箱項目にする。
  外部送信は既存 Attention delivery の設定・Policy・配信状態管理に従う。

`Scheduler tick → 有界ワーカー → Project 設定・タスクの分割走査 → 条件評価 → 永続通知キュー
→ idempotent な Attention 受付 → 既存 Shell/SSE/任意の配信 outbox` の流れ。

schema 11 → 12 は、既存バックアップ付き自動移行で以下のテーブルを追加する。
`secretary_settings`、`secretary_followup_state`、`secretary_notices`、`secretary_dependencies`。
Phase 1 の台帳・履歴・出典を維持する。設定・確認位置・通知 ID・試行回数・次回時刻・観測 revision は再起動後も残る。

### 有効化と操作

全体の `rei.secretary.enabled` に加え、背景処理は以下を明示設定し、アプリを再起動する。

```yaml
rei:
  secretary:
    enabled: true
    followups-enabled: true
```

背景処理の既定値は false。Project 設定も未登録なら監視・通知とも OFF、報告時刻は空。
`rei.agent-scheduler.enabled` は複雑なエージェントの予約実行用であり、このルール監視のためには ON にしない。
既存 `rei.agent-scheduler.check-interval-ms` の定期起動を利用する。

```text
/secretary settings --project PROJECT
/secretary configure --project PROJECT --watch-enabled --notifications --zone Asia/Tokyo --briefing-time 09:00 --review-time 18:00 --repeat-seconds 86400 --notification-priority 2 --destination INBOX
/secretary followup-status --project PROJECT
/secretary check --project PROJECT
/secretary notifications --project PROJECT --limit 100
/secretary briefing --project PROJECT
/secretary review --project PROJECT --date 2026-10-26
```

Shell と軽量 CLI の両方で使える。configure は設定全体の置換。監視・通知のフラグを省略すれば OFF にする。
時刻は HH:mm、空でその報告を停止。zone 未指定なら既存 Clock の zone、既存設定があればその zone を使う。
再通知間隔は60秒〜30日、優先度は1〜5。設定がない場合も手動報告は利用できる。
check は指定 Project の有効な監視だけを手動評価し、同じ Project の通知を処理する。背景処理の ON は不要。

通知先は `INBOX`（ローカル受信箱・Shell/SSE）か `DELIVERY`（既存の外部配信設定を利用）。
DELIVERY を選ぶだけでは外部送信を許可しない。既存 Attention の対象 Project・通知先・provider と
`deliverAttention` の Policy が必要。外部配信は既存仕様の bounded metadata のみで、タスク本文を追加送信しない。
実際の配送結果は既存 `/attention delivery` で確認する。秘書側の DELIVERED はローカル受付の確認であり、
Slack/Webhook への送信完了を意味しない。

### 待機と完了条件

承認済みタスクの references に `dependency:依存ID` を設定すると、全条件 COMPLETED で WAITING → OPEN に戻す。
`completion:dependency:依存ID` は人が明示設定する完了条件で、全条件 COMPLETED の場合だけ DONE にする。
候補・取消・完了済みのタスクは自動変更しない。単なる Run 成功や自由文から DONE と判断しない。
外部入力を条件定義・実行許可と解釈しない。依存が不明・取得失敗なら成立とはみなさない。
状態表示にキャッシュした依存事実を含め、既存の `/dependency` から詳細を確認できる。

期限当日・翌日までの接近、期限超過、待機、1日以上更新のない未完了を再確認する。
DONE/CANCELLED では依存評価と期限監視を停止し、残っている不要通知を配信直前にも抑制する。
完了通知は監視設定後の完了だけを対象とし、過去の完了作業を起動時に一斉通知しない。

### 報告と通知の制御

朝夕の報告は、今日・期限接近・重要事項・待機・前日完了・継続・優先候補、
今日完了・未完了・新規・期限超過・持越し・14日以上放置の項目を決定的に集計する。
既存の内部予定・現在の作業投影も `context` に含める。外部カレンダー接続はまだ必須ではない。
報告は最大1,000タスクで `partial` を返し、任意プロバイダーは最大16個・各256項目とする。
手動報告は読み取りだけで、タスクの承認・実行・状態変更は行わない。

定時報告は設定時刻を過ぎた最初の確認で、そのローカル日付について一度通知する。停止中の当日分は追いつき通知し、
過去日を一括送信しない。日付の重複時間・再起動でも同じ通知 ID を使う。
通知には集計件数と詳細確認のコマンドを含める。タスク通知は同じ内容の再通知間隔を永続的に検査する。

一度に最大16 Project・各64タスクを永続カーソルで巡回し、最大256 Project を管理する。
通知受付は一回最大8件・優先度順。各通知の受付試行は5回まで、60秒から指数バックオフ、最大1時間。
SENDING は60秒の利用期限後、同じ ID を安全に再送できるローカル受付だけを再試行する。
5回目で結果未記録のまま停止した項目は期限後に FAILED / delivery_result_unconfirmed として残し、自動再試行しない。
通知停止・通知先変更・タスク終端を配信直前に再検査し、古い通知を SUPPRESSED にする。
既に受付・配送中の通知を取り消す機能ではない。

通知履歴は直近から最大1,000件。Pending/Sending の次回時刻、試行数、安全な失敗種別を確認できる。
履歴保存量はタスク数と通知頻度に比例する。重複キーの確認は読み取りで済ませ、変更のない確認位置を再書き込みしない。

### API と検証

既存認証下の `/api/v1/secretary` に以下を追加する。

| 操作 | API |
| --- | --- |
| 設定確認・全体置換 | GET / PUT `/settings` |
| 手動ブリーフィング・レビュー | GET `/briefing`, `/review`（projectId, 任意 date） |
| 通知履歴 | GET `/notifications`（projectId, limit） |
| 監視・通知・依存の状態 | GET `/followup/status`（projectId） |
| 手動の条件確認 | POST `/check`（projectId） |

PUT は projectId, enabled, notifications, briefingTime, reviewTime, zone,
repeatSeconds, notificationPriority, destination。省略値は安全な既定値で補い、未知の項目を拒否する。

テストは Clock・通知受付・依存情報・HTTP を差し替え、実 LLM・実外部サービスに接続しない。
期限、朝夕報告、再起動、重複、競合、再通知境界、再試行上限、結果不明の lease、通知先変更、
条件成立・古い観測・欠落イベントの回復、旧台帳保存、OFF 時の手動報告、CLI/API を検証する。

```powershell
.\mvnw.cmd -B -Pfull '-Dtest=Secretary*Test,AgentScheduleDispatcherTest,PackageArchitectureTest,ArchitectureGuardTest' test
.\mvnw.cmd -B -Pintegration verify
.\mvnw.cmd -B -f terminal/pom.xml verify
.\mvnw.cmd -B checkstyle:checkstyle
.\mvnw.cmd -B -DskipTests compile spotbugs:spotbugs
.\.github\ci\static-analysis-gate.ps1
```

実 Slack / Webhook の配送は、この Phase のテストで検証済みとは扱わない。

### Phase 2 のローカル検証記録

バックエンド13件・軽量CLI1件を追加した。最初の全体回帰は6,307件、failure 0 / error 1 / 既存 skip 5。
error は新規の配線テストが、Publisher も実装するイベントバスと別の Publisher を二重登録した fixture の問題で、
重複を削除した。同じ検査を含む関連45件の最終 Maven verify は failure/error/skip 0、実行可能 JAR 生成も成功した。
修正後の最終コミットで `-Pfull test` も実行し、6,307件、failure/error 0・既存 skip 5で成功した。
PR #151 の回帰・静的解析 CI も成功し、main の `1f238e7a` にマージした。
最初の回帰 CI は20分のプロセス上限・30分のジョブ上限を超えて応答せず、通常キャンセルにも応答しなかったため
強制キャンセルして同一コミット・同一ジョブを再実行した。最初の実行の完了ログは取得できなかった。
検査対象・上限・基準は変更していない。

Checkstyle 0件、SpotBugs 既存1,897件・新規0件。既存の増分ゲートと自己試験8ケースが成功した。
軽量 CLI126件とランチャー6件の Maven verify も成功した。既存の依存許容基準・静的解析基準・テスト除外は変更していない。

## Phase 3：状況に応じた提案と割り込み制御

Secretary Orchestrator はローカルの軽量なルール評価を担当する。Planning Loop を置き換えず、LLM は呼ばない。
既存の Scheduler tick と、所有 Project の Run 終了・失敗、依存更新・完了、Goal 更新イベントで評価を起動する。
イベント本文から命令・権限・完了を抽出しない。ワーカー1本・queue 1件で起動をまとめ、終了時は割り込みと最大5秒の待機を行う。

### 設定と操作

```yaml
rei:
  secretary:
    enabled: true
    followups-enabled: true
    orchestrator-enabled: true
```

背景の提案は既定 OFF。Project 設定も未登録なら提案 OFF、Phase 2 の通知挙動は維持する。
明示的に Project の Orchestrator 設定を登録すると、その Project の秘書通知に停止・一時抑制・集中・頻度制限を適用する。
一般の承認待ちなど、秘書以外の Attention 通知は変更しない。

```text
/secretary orchestrator-settings --project PROJECT
/secretary orchestrator-configure --project PROJECT --proposals-enabled --minimum-seconds 900 --repeat-seconds 86400
/secretary orchestrator-status --project PROJECT
/secretary suggest --project PROJECT
/secretary suggestions --project PROJECT --limit 100
/secretary suggestion ID --project PROJECT
/secretary suggestion-history ID --project PROJECT
/secretary feedback ID --project PROJECT --revision REV --reaction LATER --delay-seconds 3600
/secretary release --project PROJECT
```

configure は設定全体の置換で、`--proposals-enabled` の省略は提案を OFF にする。
`--stopped` は緊急を含む秘書通知を停止し、配信待ちを SUPPRESSED にする。
`--snoozed-until` は ISO-8601 の Instant（例 `2026-10-26T01:00:00Z`）で、現在から最大30日まで。
一時抑制は緊急を含めて HELD にする。解除するには設定を置換してフラグ・時刻を省略する。
既にローカル受付・外部配送を開始した通知の取り消しは行わない。

minimum-seconds は60秒〜1日、repeat-seconds は60秒〜30日。
前者は Project 全体の低優先度通知の間隔、後者は同一提案の再提示間隔。
手動 suggest は背景設定を ON にしなくても、Project の提案設定が ON なら評価する。
通知するには Phase 2 の Project 監視・通知も明示的に ON にする。通知 OFF でも提案一覧と反応は利用できる。
背景通知処理は followups-enabled、外部配送は既存 Attention 設定・Policy に従う。

### 提案の根拠

候補・完了・取消を除くタスクから、期限超過、当日・翌日の期限、次工程、待機、重要事項、14日放置、7日停滞、
保存作業文脈の継続候補を選ぶ。提案には rule、taskRevision、優先度、根拠、作成/更新時刻、次回可能時刻を保存する。
期限超過は優先度1として扱う。それ以外はタスクの優先度を使い、通知 outbox は優先度順に処理する。

`after:タスクID` は、同じ Project の指定した前工程がすべて台帳上 DONE の場合だけ次工程の根拠にする。
依存の解放・完了条件は Phase 2 の `dependency:` / `completion:dependency:` を利用する。
Work Context の過去・現在・次作業は保存された申告として扱い、完了の証明にしない。
`work-context:項目ID` などの保存参照と、利用できる内部予定・日次計画を文脈として参照する。
保存文脈や予定の文章は命令や許可に変換しない。

一回の評価は64タスク、最大16 Project を巡回し、カーソルを保存する。設定は最大256 Project。
文脈の集計は Phase 2 と同じ最大1,000タスク・16 provider・各256項目の上限を使う。
提案 ID は Project・タスク・rule から決め、生成と通知予約を同じトランザクションで保存する。
同時評価・再起動・繰り返しイベントでも同じ提案を連続提示しない。

### 作業状態と保留

既存 Desktop Activity の保存済み観測を最大120秒・256件まで読み、確信度0.7以上の coding / research /
documentation を集中候補とする。画像・タイトル・本文を秘書側へコピーせず、新しいキャプチャや LLM 処理は起動しない。
監視 OFF、未観測、古い観測、低確信度、読み取り失敗は UNKNOWN。idle は IDLE とし、離席・作業終了・テスト実行を断定しない。
既存のエージェント実行追跡も利用し、実行中なら AGENT_BUSY とする。

集中候補・実行中の優先度2〜5は HELD、優先度1は集中・頻度制限を越えて表示できる。
停止・一時抑制は優先度1にも適用する。集中判断ができなくても期限・重要度の提案は継続する。
保留は受付失敗回数に数えない。解除時は同一 Project の最大8件を再確認して BUNDLED にし、
件数と履歴確認先を含む DIGEST を既存 outbox に予約する。同じ元通知を複数のまとめへ入れない。
解除直前にも停止・一時抑制・通知先・タスク終端・提案への反応を検査し、不適切なものを抑制する。

### 反応と承認

| reaction | 結果 |
| --- | --- |
| EXECUTE | EXECUTION_REQUESTED として記録。タスクを完了扱いにしない |
| LATER | DEFERRED。対象タスクを指定秒後（省略時1時間、最短60秒）まで抑制し、その後に再評価可能 |
| DISMISS | DISMISSED。同じ提案の再提示間隔まで抑制 |
| NEVER | MUTED。そのタスクの別 rule の提案も停止 |
| ALLOW_AUTO | 既存 Approval に PENDING の準備許可要求を作成し、APPROVAL_REQUESTED として記録 |

反応は current revision を必須とし、古い revision は409。反応後の古い提案通知も抑制する。
実行・許可要求ではタスクの revision と OPEN を再検査する。実行要求・承認待ちの間は同じタスクの別提案も抑制する。
MUTED の解除は、その提案の最新 revision で LATER を明示する。

ALLOW_AUTO は許可済みとは表示しない。返された approvalId を既存 `/approval show ID` で確認し、
`/approval approve ID` または `/approval deny ID` で判断する。`--continue` は指定しない。
許可の対象は Project・タスク・種類を限定した準備作業一回、有効期限15分。
対外的・破壊的な個別操作やツール権限を上書きしない。実際の実行は後続の実行制御へ引き継ぐ。
許可管理が利用できない場合は保存を拒否し、自動許可へフォールバックしない。

### API・保存先・検証

既存 Bearer 認証下の `/api/v1/secretary` に以下を追加する。

| 操作 | API |
| --- | --- |
| Orchestrator 設定 | GET / PUT `/orchestrator/settings` |
| 状態・現在の活動推定・保留件数 | GET `/orchestrator/status` |
| 手動評価・保留のまとめ | POST `/orchestrator/evaluate`, `/orchestrator/release` |
| 提案一覧・詳細・履歴 | GET `/suggestions`, `/suggestions/{id}`, `/suggestions/{id}/history` |
| 反応 | POST `/suggestions/{id}/feedback` |

GET は projectId、一覧は limit（1〜1,000）。POST は projectId、反応は revision・reaction・任意 delaySeconds。
PUT は projectId・enabled・stopped・snoozedUntil・minimumSeconds・repeatSeconds。未知の項目を拒否する。

schema 12→13 は起動時バックアップ付きの追加移行で、Orchestrator 設定・提案・提案履歴と通知検索 index を追加する。
台帳・既存監視設定・通知履歴を保存し、許可は既存 memory-consolidation.db の tool_approvals に保存する。
提案と履歴の保存量はタスク・rule 数と再提示頻度に比例する。履歴の自動削除は行わない。
LLM 呼び出し・新しい画面キャプチャ・外部ポーリングは増やさない。

固定 Clock、ローカル DB、差し替えた観測・通知受付で、停止・再開・頻度・保留・まとめ・期限・全前工程・文脈・
フィードバック・再起動・並行評価・改訂競合・既存承認・ワーカー停止・旧DB保存・HTTP/CLI・認証を検証する。
実 OS 上の観測精度や実外部配送を、この Phase で検証済みとは扱わない。

### Phase 3 のローカル検証記録

バックエンド15件・軽量CLI1件を追加し、既存の Spring 配線・認証テストも拡張した。
基本の5件を先に記述して未実装の Red を確認し、再起動・並行・日付境界・停止・保留・反応の契約へ広げた。
全体 `-Pfull verify` は6,320件、failure/error 0・既存 skip 5で成功した。
その後の遅延 provider 配線・日付境界・明示 revision・承認表示を含む最終コードは、
秘書・既存承認/権限・移行・構造ガードの135件で verify が成功し、実行可能 JAR も生成した。
最終コミットの全件確認は PR の CI と区別して報告する。

Checkstyle 0件、SpotBugs 既存1,897件・新規0件、増分ゲート・自己試験8ケースが成功した。
最初の HTTP テストで見つかった古い revision の500応答は409へ修正した。
新規の参照保持2件は遅延 provider と読み取り用 port へ変更し、解析基準の追加・除外は行っていない。
CLI127件・ランチャー6件の terminal verify も成功した。既存ランチャーテストの子プロセス終了要求と
一時ディレクトリ削除が競合したため、finally で終了完了を待つよう修正した。検証内容や除外条件は弱めていない。
