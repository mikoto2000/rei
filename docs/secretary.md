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
まとめ通知自体を再び一時抑制した場合は、元の ID と件数を保持して再開し、別のまとめへ包み直さない。

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

バックエンド16件・軽量CLI1件を追加し、既存の Spring 配線・認証テストも拡張した。
基本の5件を先に記述して未実装の Red を確認し、再起動・並行・日付境界・停止・保留・反応の契約へ広げた。
全体 `-Pfull verify` は6,320件、failure/error 0・既存 skip 5で成功した。
その後の遅延 provider 配線・日付境界・明示 revision・承認表示を含む最終コードは、
秘書・既存承認/権限・移行・構造ガードの136件で verify が成功し、実行可能 JAR も生成した。
まとめ通知の再保留で ID・件数が変わる問題も先行テストで Red を確認し、同じ通知で再開するよう修正した。
最終コミットの全件確認は PR の CI と区別して報告する。

Checkstyle 0件、SpotBugs 既存1,897件・新規0件、増分ゲート・自己試験8ケースが成功した。
最初の HTTP テストで見つかった古い revision の500応答は409へ修正した。
新規の参照保持2件は遅延 provider と読み取り用 port へ変更し、解析基準の追加・除外は行っていない。
CLI127件・ランチャー6件の terminal verify も成功した。既存ランチャーテストの子プロセス終了要求と
一時ディレクトリ削除が競合したため、finally で終了完了を待つよう修正した。検証内容や除外条件は弱めていない。


### Phase 3 の最終 CI

PR #152 の最終コミット `11049c4` は回帰 CI 6,323件・failure/error 0・既存 skip 5、静的解析とも成功し、
`1b778201` で main へマージした。専用 worktree とローカルブランチは削除済み。

## Phase 5: 許可範囲内の実行

秘書の実行は既存の会話実行・Planning/SubAgent・GoalLoop・Policy/Approval・成果物保存を使用する。
別のモデル実行ループは追加しない。既定は Project の実行無効・自動準備無効。
`rei.secretary.enabled=true` と `rei.tool-permission.enabled=true` が必要で、LLM 準備と Goal の実行には
`rei.conversation.concurrent-enabled=true` と既存の Run 管理・承認継続基盤が必要。
利用できない場合は BLOCKED とし、書込可能モードへ切り替えない。

| レベル | kind | 権限 |
| --- | --- | --- |
| 1 | RESEARCH / ORGANIZE / SUMMARY / PREPARE / STATUS | READ_ONLY の既存会話実行。取得・整理・報告・実行準備のみ |
| 2 | DRAFT / UPDATE_TASK | 既存の明示的 AUTO_APPROVE ポリシー、または対象内容に結び付けた一回の承認 |
| 3 | GOAL / ROUTINE | 人間が作成済みの Goal を指定し、その定義・予算を含む一回の承認 |

DRAFT は immutable な内部 Artifact の保存で、メール送信等は行わない。
UPDATE_TASK は指定した改訂の受理済みタスクだけを変更し、履歴と実行参照を同じ DB トランザクションで保存する。
GOAL / ROUTINE は元の GoalLoop が実際のツールを選択し、各ツールの既存権限・承認を引き続き検査する。
秘書の承認で対外操作や破壊操作を包括許可しない。メール送信等の外部操作は Phase 4 の個別経路で制御する。

### 設定と操作

Project と、その Project に属する既存 Session を指定する。

```text
/secretary autonomy-configure --project PROJECT --session SESSION --autonomy-enabled --max-level 2 --max-concurrent 1 --max-seconds 300 --max-llm-calls 8 --max-daily-calls 32 --max-retries 1
/secretary autonomy-settings --project PROJECT
/secretary autonomy-status --project PROJECT
/secretary work-add TASK --project PROJECT --revision TASK_REVISION --key request:1 --kind RESEARCH
/secretary work-show JOB --project PROJECT
/secretary work-start JOB --project PROJECT --revision JOB_REVISION
/secretary work-list --project PROJECT
/secretary work-history JOB --project PROJECT
/secretary work-suspend JOB --project PROJECT --revision JOB_REVISION
/secretary work-resume JOB --project PROJECT --revision JOB_REVISION
/secretary work-cancel JOB --project PROJECT --revision JOB_REVISION
/secretary work-verify JOB --project PROJECT --revision JOB_REVISION
```

`work-add` の `--revision` は対象タスク、開始以降の操作は実行自身の現在 revision。省略・競合を拒否する。
`--key` は Project 内の冪等キー。同じ要求は同じ実行を返し、同じキーの別内容は409。
UPDATE_TASK は `--kind UPDATE_TASK --status WAITING` 等、Goal は `--kind GOAL --goal GOAL_ID`。
返された permitId は既存 `/approval show ID`、`/approval approve ID`、`/approval deny ID` で処理する。
秘書用承認に `--continue` は不要。承認後は Scheduler または `work-start` が再検査して一度だけ消費する。
承認期限切れ・拒否後は新しいキーで実行を登録し直し、別の承認を要求する。

自動準備には、上記に加えて `rei.secretary.autonomy-enabled=true` と Project の `--auto-prepare` を指定する。
`--auto-kind` は既定 PREPARE。DRAFT を指定する場合はレベル2と承認条件が必要。
Phase 3 の EXECUTE は明示実行要求、ALLOW_AUTO は既存の準備承認を引き継ぐ。
有効化前に保存された古い明示要求は拾わない。提案の延期・停止・削除等は開始前に再検査する。
自動巡回は16 Project・各32提案ずつで、永続カーソルにより後続も処理する。
`autonomy-check` は巡回を一回実行する。通常は既存 Scheduler が同じ処理を呼ぶ。

### API

すべて既存 Bearer 認証下の `/api/v1/secretary`。

| 操作 | API |
| --- | --- |
| 設定・状態 | GET / PUT `/autonomy/settings`, GET `/autonomy/status` |
| 巡回 | POST `/autonomy/check` |
| 登録・一覧 | POST / GET `/executions` |
| 詳細・履歴 | GET `/executions/{id}`, `/executions/{id}/history` |
| 制御 | POST `/executions/{id}/start`, `/cancel`, `/suspend`, `/resume`, `/verify` |

設定 PUT は projectId / enabled / sessionId / maxLevel / autoPrepare / autoKind / maxConcurrent /
maxSeconds / maxLlmCalls / maxDailyCalls / maxRetries。CLI と同じ既定値。未知フィールドは拒否する。
登録 POST は projectId / taskId / taskRevision / key / kind / 任意 targetStatus / goalId。
制御 POST は projectId / revision。GET は projectId、一覧・履歴には limit（1〜1,000）。
Project をまたぐ参照や Session の流用を拒否する。無効時に API 自体を公開しない。

### 実行予算・状態・検証

同時実行は全体2件、Project 内1〜2件、同一タスク1件。未終端実行は全体256件まで。
時間10〜3,600秒、モデル呼出0〜100回、日次0〜10,000回、再試行0〜3回。
呼出の直前に storage.db へ予約し、失敗・再開でも戻さない。日付は既存ブリーフィングの Project タイムゾーン。
Goal は元の残予算を日次枠へ先に全量予約するため、使用しなかった分も保守的に計上する。
元 Goal の maxRuns・呼出上限・トークン上限・段階・完了条件は変更しない。
残予算が秘書側上限を超える Goal は登録できない。Project 設定の増額で実行作成時の上限を拡大しない。

初回開始時の絶対期限には一時停止・バックオフ時間も含む。再開は期限・試行・呼出上限をリセットしない。
読み取り準備の一時障害だけを上限付きで再試行する。副作用の不明なものを自動再実行しない。
WAITING は既存の承認継続経路と同じ予算を保持する。再起動後に親予算が復元できない承認継続は既存基盤が UNKNOWN にし、
秘書側は UNCERTAIN として明示確認を要求する。

準備成功は PREPARED で、元タスクを DONE にしない。結果本文は最大32,768文字、SHA-256 を保存する。
DRAFT は Artifact の実バイトと SHA を検証して COMPLETED、UPDATE_TASK は保存改訂と実行参照で検証、
Goal は既存の独立したファイル条件・必須チェック・段階を満たして COMPLETED とする。
実行者の「完了した」という文章だけでは完了にしない。
取消・一時停止・時間超過は実行停止の確認まで中間状態に留める。
書込可能な Goal を途中停止した場合は、停止確認だけでは副作用の成否を証明できないため UNCERTAIN とする。
その実行を直接 resume せず、実際のファイル・外部状態・Goal 履歴を確認し、元の残予算を保持する Goal に
新しい実行キーと承認を与える。読み取り準備の確認済み PAUSED は通常の work-resume で再開できる。
FINALIZING の保存処理が始まったものは取消済みと偽って返さず、その結果を検証する。
不明な外部影響・クラッシュ境界は UNCERTAIN。`work-show`、実際の成果物・Goal 履歴を確認してから操作する。

実行結果と承認要求は既存の通知 outbox へ登録し、集中・停止・まとめ通知の既存ルールを通す。
通知を受けるには Phase 2 の通知設定が必要。日次 briefing/review の context にも実行結果を含む。

### 保存・コスト・実環境確認

schema 13→14 を起動時バックアップ付きで追加し、`secretary_autonomy_settings`、`secretary_jobs`、
`secretary_job_history`、`secretary_model_budget` を storage.db に保存する。台帳・提案・保留通知を保持する。
内部承認は既存 tool_approvals、下書きは既存 ArtifactStore の容量・有効期限・所有者保護に従う。
実行履歴と日次予算は自動削除しないため、実行数に比例して DB 使用量が増える。
自動準備を有効にすると実モデル利用料金が発生し得る。日次と実行ごとの呼出上限を小さく設定して開始する。

固定 Clock・SQLite・実際の会話 Gateway / GoalLoop / ArtifactStore とモデル代替により契約を検証する。
実 LLM、実外部送信、実サービス資格情報を用いた動作は、この Phase では検証済みと扱わない。


### Phase 5 のローカル検証記録

バックエンド17件と軽量CLI1件を追加し、既存の認証・移行 fixture を拡張した。
最初の6件を未実装の Red から実装し、承認前再開と取消後の更新の2競合も Red で再現して修正した。
書込可能 Goal の中断を安全な一時停止と誤認する問題も先行テストで再現し、UNCERTAIN と明示確認へ変更した。

途中の全件 `-Pfull verify` は6,337件・構造ガード1失敗・error 0・既存 skip 5。
構造の依存追加を既存の予算管理経路へ修正し、基準の追加・除外なしで構造ガードを通した。
その後、秘書・Goal・成果物・既存承認/権限・移行・構造の317件で verify と JAR 生成が成功した。
承認待ちの公平性変更で既存の再試行順序が変わった問題は、元のテストを維持して修正した。
最終の実行管理可用性・Goal 定義固定・秘書API・認証・構造を含む74件でも verify と JAR 生成が成功した。
最終コミット全件の確認は PR の CI と区別して報告する。

最終 Checkstyle 0件、SpotBugs 既存1,895件・新規0件、増分ゲートと自己試験8ケースが成功した。
静的解析の新規指摘は広すぎる例外再送出と SQL 準備の表現を修正して解消し、基準を変更していない。
terminal verify は CLI128件・ランチャー6件、failure/error/skip 0 で成功した。


## Phase 4: 外部サービス統合

`SecretaryConnections` が Project / Session / 接続名 / サービス / リソースを固定し、
サービスを所有する adapter を `SecretaryProvider` port 経由で使用する。
読み取りの定期巡回、変更証拠、タスク候補、書込み要求・承認・結果は個別の状態を持つ。
Google は既存 OAuth / 更新ジョブ / Calendar service、Slack は既存 admission / transport、
GitHub は既存署名検証済み Webhook fact と新しい固定 REST 読取りを再利用する。
モデルに任意 URL・トークン・送信先を選ばせる MCP ツールは追加しない。

### 設定と接続

グローバル巡回、サービス、接続ごとの enabled は別々。新設項目は既定 OFF。
手動 poll もレート制限・接続ごとの next を守る。無効な資格情報を対話なしで再認可しない。

```yaml
rei:
  secretary:
    enabled: true
    connections-enabled: true
    github:
      enabled: true
    slack:
      enabled: false
  google:
    calendar:
      enabled: true
    gmail:
      enabled: true
      drafts-enabled: false
      send-enabled: false
```

`NETWORK_READ` を既存 `rei.tool-permission.auto-approve` に明示追加する。
権限機構が無効なら外部連携は fail closed。書込みには `NETWORK_WRITE` / `EXTERNAL_SIDE_EFFECT`、
Calendar の取消にはさらに `DESTRUCTIVE` が必要。拒否設定が自動承認より優先する。
通常の書込みは同じポリシーから承認要否を決定するが、**MAIL_SEND は必ず個別承認**する。
Calendar も読取り専用で使う場合は、ポリシーで NETWORK_WRITE を拒否して運用する。

| サービス | 手動設定・資格情報 | 最小限の権限と制約 |
| --- | --- | --- |
| Calendar | 既存 Google Desktop OAuth client JSON / token directory。resource は `primary` または既知の calendar ID | 既存 Calendar Events scope と空き時間用 `calendar.events.freebusy`。Tasks を使わない場合は既存 task.enabled を OFF |
| Gmail | 同じ Google OAuth。resource は `me` 固定。Google Cloud 側で Gmail API を有効にする | 読取り `gmail.readonly`。下書き保存時だけ `gmail.compose`、送信のみなら `gmail.send` を追加。compose 自体は送信権限も含むがアプリの send-enabled と個別承認は別途必要 |
| GitHub | 環境変数 `GH_TOKEN`（優先）または `GITHUB_TOKEN`。resource は `owner/repository` | 対象 repository だけの fine-grained PAT、Metadata / Pull requests / Issues / Checks / Commit statuses の Read。Classic PAT なら private repository に広い repo 権限が必要になり得る |
| Slack | 既存 `SLACK_BOT_TOKEN` と `rei.slack` の workspace / channel / user allowlist / channelProjects または dmProject | bot `chat:write`、対象会話の history scope。公開・非公開チャンネルの thread 読取りには任意の `SLACK_USER_TOKEN` と対応する history scope が必要。Bot と同 workspace かつ allowlist の本人のみ |

Google の token directory は既存 FileDataStore を共用する。秘密は DB に複製しない。
現在必要な scope の SHA-256 marker を同じ directory に保存し、marker がない旧 credential や
scope 変更は明示再認可を要求する。バックグラウンド処理からブラウザを開かない。
端末本人だけが読める Windows ACL と、同期・Git 管理対象外の data directory を利用する。
既存の tokens-directory と client JSON を秘密情報として管理する。
資格情報や API 本文を診断に含めず、DB の identity は資格情報の SHA-256 のみ。
接続 status の CONFIGURED は保存済み資格情報の存在確認であり、実サービスでの認証成功を意味しない。

```text
/secretary connection-configure --project PROJECT --session SESSION --name calendar --service CALENDAR --resource primary --connection-enabled --poll-seconds 300
/secretary connection-configure --project PROJECT --session SESSION --name mail --service GMAIL --resource me --connection-enabled --import-candidates --poll-seconds 300
/secretary connection-configure --project PROJECT --session SESSION --name github --service GITHUB --resource OWNER/REPO --connection-enabled --import-candidates
/secretary connection-list --project PROJECT
/secretary connection-auth CONNECTION_ID --project PROJECT
/secretary connection-authorize CONNECTION_ID --project PROJECT --session SESSION
/secretary connection-poll CONNECTION_ID --project PROJECT
/secretary connection-items CONNECTION_ID --project PROJECT
/secretary connection-history CONNECTION_ID --project PROJECT
/secretary connection-task CONNECTION_ID --project PROJECT --key EXTERNAL_ITEM_ID
```

新規設定は version -1。更新には `--connection-version CURRENT_VERSION` が必須。
resource / service の変更には別の接続名を使う。Session は既存所有者と一致しなければ拒否する。
既存接続を同名で更新する際も全 Config を指定する。
`connection-authorize` は Google のみ。Web / 軽量CLI では既存 Run queue に受付けて202を返し、
SSE / Run API で対話認可 URL と終了状態を確認する。GitHub / Slack はサービス側で PAT / token を発行する。

`connection-disconnect ID --connection-version V` はその Project 接続を無効にする。
共有 Google / Slack / GitHub 資格情報は他機能への影響を避けるため保持する。
アカウントの完全なアクセス取消は Google の第三者接続、GitHub の PAT、Slack の installed app / token 管理画面で行う。
期限切れ・取消済み token は REAUTH_REQUIRED。環境変数の更新はプロセスを再起動する。

### 読取り操作

`connection-read ID --operation OP --arguments JSON` は明示的な人間向け読取り。
JSON は文字列 map で、未知引数・未知操作・任意接続先を拒否する。
保存 poll は原則 metadata のみ、メール本文・添付ファイルを一括取得しない。

| サービス | operation と arguments |
| --- | --- |
| Calendar | LIST `{from,to,page}`（最大31日）、TODAY `{date}`、DETAIL `{id}`、FREEBUSY `{from,to}`。日時は offset または接続 zone |
| Gmail | LIST `{q,page}`、UNREAD / NEEDS_REPLY `{page}`、MESSAGE / THREAD / REPLY `{id}`。返信要否は unread / IMPORTANT / subject の限定したルール |
| GitHub | LIST / PRS / REVIEWS / ISSUES / UNFINISHED `{page}`、DETAIL / CI / REVIEW_SUMMARY `{number,page}`。本人の関連 PR・Issue とレビュー待ち。review summary は reviewer ごとの状態集計で、本文の意味要約ではない |
| Slack | LIST / MENTIONS `{cursor,oldest}`、THREAD / REPLY `{ts,cursor}`。allowlist channel 内の人間のメンションだけを候補化。bot / 自分 / subtype は既存 admission と同じく除外 |

REPLY はローカルの編集用テンプレートを返す。実際の AI 下書きは後述する Phase 5 の専用経路を使う。
Calendar freebusy の部分失敗を「空き」と扱わず、CI は check と commit status の双方と head SHA を検査する。
不足権限・check 未取得・ページ途中・head 更新中は UNKNOWN / PENDING / STALE として成功を断定しない。
GitHub CI API の局所障害は PR の metadata 取得まで止めない。
メール thread は最大50件、Slack15件、GitHub20件/page。partial / SYNCING を確認し、必要なら続きの cursor で取得する。

### 変更・送信・進捗報告

書込みは `external-prepare --connection ID --key UNIQUE --operation OP --arguments JSON` で
具体的な送信内容を保存してから承認を要求する。接続先・account identity・policy・設定版も束縛する。
承認表示は既存 credential redactor を通す。本文を変更する際は新しい key と承認が必要。

| operation | arguments |
| --- | --- |
| CALENDAR_CREATE | title / start / end / 任意 description / location |
| CALENDAR_UPDATE | id / etag と変更する title / start / end / description / location |
| CALENDAR_CANCEL | id / etag |
| MAIL_DRAFT / MAIL_SEND | to / 任意 cc / subject / body / 任意 threadId / inReplyTo / references。送信には任意 trackReply（既定 true） |
| SLACK_POST | body / 任意 thread |
| SLACK_REPORT | taskId / taskRevision / 任意 thread。現在のタスク名・状態を承認対象本文に固定 |

宛先は plain address のみ、CRLF によるヘッダ追加を拒否する。返信の threadId には In-Reply-To が必要。
Slack は機械投稿の prefix と action ID を承認前に本文へ追加し、mention の特殊文字を escape する。
既存 bot 入力経路が自己投稿を再受付しない規則を維持し、metadata にも機械投稿識別を付ける。
進捗報告は明示操作と権限の対象で、タスク状態変更から無条件に Slack 投稿しない。

```text
/secretary external-list --project PROJECT
/secretary external-show ACTION_ID --project PROJECT
/approval show PERMIT_ID
/approval approve PERMIT_ID
/secretary external-execute ACTION_ID --project PROJECT --revision CURRENT_REVISION
/secretary external-history ACTION_ID --project PROJECT
/secretary external-verify ACTION_ID --project PROJECT --revision CURRENT_REVISION
/secretary external-cancel ACTION_ID --project PROJECT --revision CURRENT_REVISION
```

承認は15分・一回限り、アクション受付も15分で失効する。全体2件 / 同じ接続1件の書込みに制限する。
同じ key の再要求は同じ action。既知の429だけ最大3試行し、Retry-After と next を保持する。
SENDING を書込み前に永続化し、再起動時の SENDING は UNCERTAIN。タイムアウトや不明な ACK を自動再送しない。
UNCERTAIN の verify は独立した読取りだけで照合する。Gmail の決定的 Message-ID、
Calendar の決定的 event ID / 更新内容 / 取消状態、Slack の action marker と bot 本人を確認する。
見つからない場合は UNCERTAIN のまま。手動再送も重複し得るため実際のサービス画面と履歴を確認する。

COMPLETED は独立した外部読取りで確認した状態。API の200や AI の自己申告だけで成功にしない。
確認済み MAIL_SEND は thread に結びつく WAITING タスクを一度だけ作る。
巡回時に返信待ち thread を3件ずつ公平に観測し、新しい inbound を検知したら WAITING→OPEN に戻す。
人が DONE / CANCELLED にしたタスクは再開せず、返信だけで DONE にしない。
外部情報からの自動 import は CANDIDATE に限定し、会話由来の確定タスクへタイトル一致だけで自動結合しない。

### プライバシーと AI 下書き

外部起源・external reference のあるタスクは、通常の会話 planner へ自動投入しない。
外部本文をモデルへ送るには、次の **全て** を明示設定する。

```yaml
rei:
  secretary:
    external-content-to-model: true
    private-model-endpoints: http://127.0.0.1:1234/v1
  llm:
    features:
      secretary-reply:
        base-url: http://127.0.0.1:1234/v1
        model: EXPLICIT_PRIVATE_MODEL
        api-key: ${PRIVATE_REPLY_API_KEY:dummy-key}
```

加えて接続設定を現在 version で更新し `--allow-model-content` を指定する。
HTTPS または loopback HTTP のみで、URL 内の認証情報・query・fragment を拒否する。
専用 model / endpoint の完全一致が必要で、通常 CHAT / fallback / capture を使わない。
取得した情報を最大12,000文字へ制限し、既存 SensitiveInfoDetector の規則で secret / PEM を拒否、
メールアドレス・電話番号を伏字にする。一般的な機密判定が完全であることは保証しない。
外部テキストを untrusted evidence として一回だけモデルに渡し、ツール・会話履歴・記憶統合・追加閲覧を使用しない。
モデル出力の tool calls は失敗にし、最大45秒・32,768文字・自動 SDK retry 0。
専用 HTTP client は再利用し、稼働中の専用モデル変更は拒否する。設定変更時はアプリを再起動する。
Phase 5 の実行ごと・日次予算を呼出前に予約し、取消と絶対期限を共用する。

候補を承認後、`work-add TASK_ID --kind DRAFT --key KEY --revision V` と通常の承認・work-start を使う。
生成物は既存 ArtifactStore に保存され、送信されない。人が確認して別途 external-prepare する。
設定版 / evidence revision / model identity を permission に固定し、開始前と各呼出直前に再検査する。
許可の取消・接続解除後の保存済み実行も拒否する。本文・生成結果を通常 briefing context に複製しない。
許可を変更した後は新しい execution key で登録し直す。

### イベント方式・保存・制約

| 方式 | 採否・理由 |
| --- | --- |
| 定期 polling | Windows ローカルの標準経路。公開 HTTP 不要。接続ごと60秒以上、最大8接続/巡回。Gmail は historyId / page cursor を永続化し失効時は full resync |
| Webhook | 既存 GitHub 署名検証・Project/Session 所有権・fact 保存を再利用。秘書の投影は1 worker / queue32。飽和時も原本 fact を保持。公開受信経路を設定した場合だけ追加の即時証拠として使う |
| Calendar watch / Gmail push | 公開 HTTPS や Pub/Sub / subscription 更新管理が必要になるため必須化しない |
| Calendar syncToken | 高速だが query 制約と410後の全同期管理が必要。本実装は昨日〜30日先の31日 snapshot を分割取得し etag / cancelled を照合。範囲外の変更は次の読取りまで追跡しない |
| Slack Socket Mode | 既存人間受付を維持。秘書は allowlist channel の bounded polling と併用。広い search.messages / search:read を追加しない |

Calendar の取消 tombstone、メール削除、GitHub head 変更を証拠更新として保存する。
同じ connection / external ID / revision を一意にし、候補・通知重複を抑止する。
外部更新は既存 Secretary outbox / Attention、日次 briefing / review に反映する。
Slack 通知先は Phase 2 の `--destination DELIVERY` と既存
[Slack notification provider](slack-notification-provider.md) の allowlist / delivery policy を組み合わせる。
この既存通知は本文を外部に送らず、Inbox ID などの metadata のみ送信する。
投稿本文を送る進捗報告とは権限・要求を分ける。

schema 14→15 は既存バックアップ付き移行で、connections / items / events / actions / action history /
reply waits の6テーブルと既存 job 用 index を追加する。古い台帳・提案・予算は保持する。
接続上限256、cache 5,000件/接続、履歴は保持して増える。本文抜粋512文字、metadata最大64KiB、
明示 action 本文最大32,768文字。保存容量は接続数・外部変更・送信履歴に比例する。
DB とバックアップには件名・抜粋・承認対象本文・AI 下書きが含まれ得る。data-dir 全体を保護する。
秘密 token は含めないが、この DB を一般公開しない。

401 は再認可、403 は権限不足、429 は保存した待機期限を確認する。あるサービスの障害で他の接続を無効化しない。
HTTP body 最大1MiB、redirect・通信自動 retry なし、同時ネットワークを制限する。
poll の部分取得は SYNCING で示し、ページが完了するまで全情報の取得済みと扱わない。
履歴の UNKNOWN / UNCERTAIN は未確認として実サービス画面で照合する。

実 Google OAuth / Google APIs / GitHub account / Slack workspace / 実 LLM には接続していない。
プロトコルと主要状態遷移はモック・固定 Clock・SQLite・既存 HTTP 認証基盤で検証する。
送信、token 更新、scope 設定、workspace 制限、モデル endpoint の動作は資格情報を設定した後に手動確認が必要。

公式仕様:
[Calendar incremental sync](https://developers.google.com/workspace/calendar/api/guides/sync)、
[Calendar freebusy](https://developers.google.com/workspace/calendar/api/v3/reference/freebusy/query)、
[Gmail scopes](https://developers.google.com/workspace/gmail/api/auth/scopes)、
[Gmail sending](https://developers.google.com/workspace/gmail/api/guides/sending)、
[Gmail threads](https://developers.google.com/workspace/gmail/api/guides/threads)、
[GitHub API versions](https://docs.github.com/en/rest/about-the-rest-api/api-versions)、
[GitHub rate limits](https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api)、
[Slack replies](https://docs.slack.dev/reference/methods/conversations.replies/)、
[Slack rate changes](https://docs.slack.dev/changelog/2025/05/29/rate-limit-changes-for-non-marketplace-apps/)。
GitHub API は2022-11-28を固定し、公式のサポート期間内を前提にする。
UNFINISHED は更新日で古い未完了作業を除外せず、LIST の直近30日・レビュー待ちと順番に巡回する。
[GitHub search の上限](https://docs.github.com/en/rest/search/search) は1検索1,000件のため50 pageまでとし、
それを超える場合は partial を示して次の種類へ進む。大きい repository で全件を観測したと主張しない。
Calendar 削除後の404 / 410は、[公式の deleted resource](https://developers.google.com/workspace/calendar/api/guides/errors)
の扱いに従って読取り照合する。
Slack history/replies は配布形態によって1分1回・15件の制限があるため、下限60秒と15件を採用し429を優先する。

### Phase 4 API

すべて既存 Bearer 認証下。秘書と Web の両方が有効な場合だけ公開する。

| 操作 | `/api/v1/secretary` 配下 |
| --- | --- |
| 接続一覧・設定 | GET / PUT `/connections`。PUT は `{config: Config全項目, version: -1または現在版}` |
| 状態・資格情報状態 | GET `/connections/{id}`, `/connections/{id}/auth`、projectId 必須 |
| poll / disconnect | POST `/connections/{id}/poll`, `/disconnect`。projectId / disconnect は revision 必須 |
| 読取り | POST `/connections/{id}/read`、projectId / operation / arguments |
| 証拠・履歴 | GET `/connections/{id}/items`, `/history`、projectId / 任意 limit |
| 候補変換 | POST `/connections/{id}/tasks`、projectId / externalId |
| 書込み受付 | POST `/external-actions`、projectId / connectionId / key / operation / arguments |
| 一覧・詳細・履歴 | GET `/external-actions`, `/{id}`, `/{id}/history` |
| 制御 | POST `/external-actions/{id}/execute`, `/cancel`, `/verify`、projectId / revision |

Google の明示認可だけは既存 `/api/v1/google-operations/secretary/authorize` に POST
（projectId / sessionId / connectionId / force）し、Run ID を返す。
認可 URL をログではなく本人の既存 Run 表示に返す。OAuth の token は含めない。
未知 JSON フィールドを拒否し、設定版・実行版の競合は409、外部 rate limit は429、その他の外部障害は503。


## 全 Phase を通した手動確認

まず既存の方法で Project と Session を作成し、現在の ID を控える。以下の TASK_ID / EXECUTION_ID /
PERMIT_ID / CONNECTION_ID と revision は各コマンドの応答に置き換える。古い値を推測して再利用しない。
外部送信の試験には本人が管理するテスト宛先とテストチャンネルを使う。

1. `rei.secretary.enabled=true`、`extraction-enabled=true` で起動し、既存の会話から
   「明日テスト報告を作成したい」と入力する。`/secretary list --project PROJECT` で CANDIDATE と出典を確認する。
   モデル不要の確認なら `/secretary extract --project PROJECT --key smoke:one --title "明日テスト報告を作成したい"` を使う。
2. `/secretary approve TASK_ID --project PROJECT --revision V` で OPEN にする。承認だけでは Run が開始しないことを確認する。
3. `/secretary update TASK_ID --project PROJECT --revision V --title "テスト報告" --intent TODO --status OPEN --due YYYY-MM-DD --priority 2`
   で期限を設定する。update は置換なので、残すタグ・参照も明示する。
4. `rei.secretary.followups-enabled=true` と既存 scheduler を有効にする。
   `/secretary configure --project PROJECT --watch-enabled --notifications --zone Asia/Tokyo --briefing-time 09:00 --review-time 18:00`
   の後、`/secretary check --project PROJECT`、`followup-status`、`notifications` を確認する。
5. `/secretary briefing --project PROJECT --date YYYY-MM-DD`、`/secretary review --project PROJECT --date YYYY-MM-DD`
   で期限・待機・実行結果を確認する。通知履歴と Attention Inbox を照合する。
6. `rei.secretary.orchestrator-enabled=true`、
   `/secretary orchestrator-configure --project PROJECT --proposals-enabled --minimum-seconds 60` を設定し、
   `/secretary suggest --project PROJECT`、`/secretary suggestions --project PROJECT` で根拠と時刻を確認する。
   LATER / DISMISS / NEVER / stop / snooze が反映されることも確認できる。
7. `rei.conversation.concurrent-enabled=true`、提案を拾う場合は `rei.secretary.autonomy-enabled=true` と Phase 5 の権限設定を有効にし、
   `/secretary autonomy-configure --project PROJECT --session SESSION --autonomy-enabled --max-level 2 --max-llm-calls 2 --max-daily-calls 4 --max-retries 0`
   で小さい予算を設定する。`/secretary feedback SUGGESTION_ID --project PROJECT --revision V --reaction EXECUTE` の後、
   `autonomy-check` / `work-list` で準備実行を確認する。直接の確認は `work-add TASK_ID --revision V --key smoke:draft --kind DRAFT`。
8. 応答に permitId があれば `/approval show PERMIT_ID` で対象を確認して approve / deny する。
   `work-start EXECUTION_ID --project PROJECT --revision V` で開始し、必要な個別ツール承認は既存手順に従う。
9. `work-show` / `work-history` / `autonomy-status` と Artifact を確認する。
   準備成功だけでタスク DONE にならず、日次・実行予算が減っていることを確認する。
   Goal を使う場合は元の必須チェック・独立した完了条件を持つ Goal を既存 UI / CLI で先に作成する。
10. Phase 4 の資格情報と接続を設定し、connection-auth → connection-poll → connection-items → connection-task の順で確認する。
    Calendar TODAY / FREEBUSY、Gmail MESSAGE / THREAD、GitHub REVIEWS / CI、Slack MENTIONS / THREAD をそれぞれ read する。
    メールまたは Slack の AI 下書きが必要なら専用モデルと接続ごとの利用許可を明示設定して DRAFT を生成する。
    本文・宛先を確認して external-prepare し、**メール送信は個別 approval**、その後 external-execute を行う。
    external-show / external-history と実サービスを照合する。返信後に WAITING→OPEN と日次報告への反映を確認する。

未設定・機能 OFF の起動、資格情報取消、429、通信切断、再起動については、同じ手動手順をテスト環境で追加確認する。
UNCERTAIN を success と見なさず、`external-verify` の読取り照合と実サービス画面を用いる。


### 前段 Phase の最終 CI 記録

| Phase | PR / merge | exact-head backend CI | 静的解析 |
| --- | --- | --- | --- |
| 1 | [150](https://github.com/mikoto2000/rei/pull/150) / `5153f3ba5bc4682d205356497dcb3260a49bfa74` | 6,294件、failure/error 0、既存skip5 | Checkstyle0、SpotBugs新規0（既存1,897） |
| 2 | [151](https://github.com/mikoto2000/rei/pull/151) / `1f238e7a48f7fb4d8fa9340d31666e76194cf359` | 6,307件、failure/error 0、既存skip5 | Checkstyle0、SpotBugs新規0（既存1,897） |
| 3 | [152](https://github.com/mikoto2000/rei/pull/152) / `1b7782010aa8a611de7dfba21f3dbd7a4e188ea2` | 6,323件、failure/error 0、既存skip5 | Checkstyle0、SpotBugs新規0（既存1,897） |
| 5 | [153](https://github.com/mikoto2000/rei/pull/153) / `3ab311ad38f03a70f80250a4536e6091ec0fbe79` | 6,340件、failure/error 0、既存skip5 | Checkstyle0、SpotBugs新規0（既存1,895） |

各 PR は exact head の回帰・静的 CI 成功後にマージし、各 worktree とローカル branch を削除してから次へ進んだ。
Phase 2 は最初の CI が期限内に終了せず、同じコミット・同じチェックを再実行して成功を確認した。
基準・除外・時間上限は緩和していない。Phase 4 の最終 head と CI はその PR と完了報告で確認する。


### Phase 4 のローカル検証記録

サービスの protocol と台帳の先行 Red、専用モデルの fallback 拒否、削除後410、未完了 Issue の古い更新日、
検索上限、承認待ちの公平性、専用 client の再利用を失敗から修正した。
既存の HTTP 改訂競合テストを維持し、新しい通信上限テストは別クラスに分けた。既存試験の削除はない。

全件の最初の `-Pfull verify` は6,371件、failure1 / error0 / 既存skip5。
唯一の失敗は新しい enabled 項目の設定テンプレート更新漏れで、生成テンプレートを修正して既存7件を再確認した。
これは最終コードの全件成功記録とは区別する。その後の関連129件、Calendar adapter 分離後109件、
送信の読取り再照合10件、全実 adapter の資格情報なし共存起動1件はそれぞれ failure/error/skip0で成功し、実行可能JARを生成した。
軽量 CLI は129件、launcher6件で verify 成功。

完全 Checkstyle は0件。SpotBugs は既存1,895件・新規0件。増分ゲートと自己試験8ケースが成功。
例外型・購読前の null 防御・承認 Map の不変化・所有 service の adapter 境界を修正し、
解析基準や除外設定を拡張していない。最終コミットの全件は PR の exact-head CI で判定する。
