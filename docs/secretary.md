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
後続 Phase の監視・提案・自律実行・外部サービス統合は、この Phase の機能ではない。

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
