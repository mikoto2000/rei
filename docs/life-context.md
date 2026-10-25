# 勤務時間制約と生活コンテキスト

## 導入と既存機能

先行チャット「れいに秘書機能を全Phase実装」は2026-10-26 02:40 JSTに成功終了した。
PR #150/#151/#152/#153/#154 がmainにマージ済みで、各最終headのJava regressionと
Java static analysisの成功をGitHub APIで照合した。基点は `974e16d4`。
今回の作業はその完了イベントを受信するまで開始していない。待機はセッション内の
`wait_threads` を使用し、承認待ち中のみツール内で5分間隔に確認した。
セッション外に残る永続ジョブや待機用worktreeは作成していない。

既存の約束台帳、Scheduler tick、SecretaryFollowupsの永続通知、Orchestratorの
保留・集約・反応履歴、Autonomyの承認・予算・Runを再利用する。
観測・通知・実行側は読み取り専用のLifePolicyを受け取り、設定変更は明示CLI/APIに限定する。
利用者単位の勤務制約をProjectごとに複製しない。設定の正本は既存storage.db内の
`life_context` 1行であり、schema 15→16は既存の起動前バックアップ付き移行で追加する。

## 設定と現在の制約

勤務の初期値はAsia/Tokyo、月〜金、09:00以上18:00未満。
祝日や有休を推測して除外しない。勤務外の予定の空白はUNKNOWN。
個人時間帯を明示登録したときだけAVAILABLE、休息・勤務はUNAVAILABLE、
一時例外でBUSY/UNKNOWN/UNAVAILABLE/AVAILABLEを明示できる。
カレンダーの空き時間はUNKNOWNとして返す。

設定は全体置換で、取得した現在のrevisionが必要。競合する変更は拒否し、再取得して編集する。
`workDays` は勤務開始曜日、`workStart` / `workEnd` はローカル時刻。
夜勤や日付をまたぐ休息にも対応し、開始時刻は含み、終了時刻は含まない。
通知・休息・個人時間帯は `days` / `start` / `end` の配列。
未設定の睡眠・個人時間を推測しない。通知時間帯が空の場合も勤務・休息制約は適用する。
一時例外のBUSYは通知を保留し、UNAVAILABLEは通知・自動観測・背景処理の開始も停止する。
例外終了後は元の制約へ戻る。AVAILABLEを指定しても勤務・休息の観測禁止は解除しない。

通常Shellと軽量CLIの両方で利用できる。設定は重要操作なのでモデル用ツールには公開しない。

```text
/secretary life-settings
/secretary life-status
/secretary life-configure --settings-json '{"revision":0,"zone":"Asia/Tokyo","workDays":["MONDAY","TUESDAY","WEDNESDAY","THURSDAY","FRIDAY"],"workStart":"09:00","workEnd":"18:00","notifications":[],"rest":[],"personal":[],"background":[],"exception":null,"emergencyNoticeIds":[]}'
```

認証付きBackend APIは `GET /api/v1/life/settings`、`GET /api/v1/life/status`、
`PUT /api/v1/life/settings`。PUT本文は `{"settings": 上記設定オブジェクト}`。
既存のBearer APIキーを使用する。資格情報をコマンド例やログへ書かない。

## 観測とバックグラウンド処理

自動デスクトップの軽量入力状態・ウィンドウ情報・画像を取得前に抑止する。
Windows OS読み取りの直前・直後と、非同期の解析・画像保存・記録保存でも再確認する。
勤務時間をまたぐ待機済み画像と設定世代が変わった処理は利用しない。
時系列活動推定にも停止判定を適用し、観測情報のLLM解析前・保存前に再確認する。
常時音声入力はマイクopen前、フレーム取得前後、認識前、補正依頼・入力配送時に確認する。
勤務中に開始・継続できなかった音声入力は勤務後に手動で再開する。
通常の文字チャットや明示的なファイル・Web操作はこの自動観測ガードの対象外で、
既存のツール権限と承認条件を引き続き適用する。
クリップボードの自動監視は追加しない。Computer Useなどの明示ツールの権限も変更しない。

勤務中の個人タスクは `background` に `{"project":"PROJECT_ID","task":"TASK_ID"}` を
明示登録した範囲だけ開始できる。外部接続の自動収集を許可する場合は
`task` を `connection:CONNECTION_ID` とする。登録しても既存の接続opt-in、実行予算、
Policy、個別承認は必要。休息中はバックグラウンド実行を開始しない。
秘書の実行中ジョブが勤務開始・設定変更で許可範囲外になった場合は、既存の一時停止・再開経路へ渡し、
新たなモデル予算の予約も拒否する。再開は既存操作で行い、権限を再確認する。
すでに別入口から実行中の通常Runをこの設定で一律停止しない。

## 通知

通知区分はEMERGENCY/NORMAL/LOW/BRIEFING。優先度1だけでは勤務中の配信を許可しない。
EMERGENCYは利用者が `emergencyNoticeIds` に登録した正確な通知IDに限る。
LLMの自由文や緊急という自己申告を根拠に昇格しない。初期の許可リストは空。
秘書通知は既存のsecretary_noticesへHELDで保存し、配送試行を消費せず、再起動後も残る。
勤務終了後は既存の最大8件・重要度順のDIGESTとして解除する。
外部配送に移った通知も既存Attention outboxで送信直前に再確認し、保留は失敗回数に数えない。
通常のAttention通知も既存agent_attentionのHELDとして保持し、解除時は1分に最大1 Project・8件を
一つのLIFE_DIGESTに集約する。未読の元項目は受信箱から引き続き確認できる。
受信箱の手動確認や通常チャット自体は利用可能。自動の話しかけも勤務・休息中は停止する。待機済みの文脈取得、候補生成の各provider呼出、候補保存、発話直前でも制約を再確認する。

設定・保留通知は同じ既存データディレクトリの管理対象で、ユーザーデータを外部へ送らない。
削除・バックアップ・復旧は既存storage/Attention/秘書の手順に従う。
全設定の初期化はrevisionを取得して初期値を明示保存する。DBを直接削除しない。

## 検証

```powershell
.\mvnw.cmd -B -Pintegration '-Dtest=LifeContext*Test,ActivityCaptureTest,Secretary*Test,*MigrationTest,AttentionDeliveryTest,VoiceInputCoordinatorTest,PackageArchitectureTest,ArchitectureGuardTest' verify
.\mvnw.cmd -B -Pfull test
.\mvnw.cmd -B checkstyle:checkstyle
.\mvnw.cmd -B -DskipTests compile spotbugs:spotbugs
.\.github\ci\static-analysis-gate.ps1
.\mvnw.cmd -B -f terminal/pom.xml verify
```

固定Clockの月曜08:59/09:00/17:59/18:00、金曜18:00、土日、翌月曜、
ゾーン変更、設定世代、休息の日付境界、再起動・競合、勤務中の取得ゼロ、
処理中の制約変更、保留の再開・集約・二重配信を検証する。
実Windowsマイク・実画面・実外部送信の精度や到達性は自動試験で確認したとは扱わない。

## Phase 1の検証記録

- 台帳の欠落した実装を参照するテストで最初のRedを確認し、固定Clockの基本4件をGreenにした。
- 一時UNAVAILABLEでも通知を許可するケース、まとめ通知の外部outbox所有情報、
  自動話しかけの待機・発話境界は先行テストで再現して修正した。
- 全件 `-Pfull verify` は6,390件、failure/error 0、既存skip 5、JAR生成成功。
  これは追加の境界修正・読み取り専用port整理前の記録であり、最終headの全件確認はPR CIで行う。
- 最終の関連211件はfailure/error/skip 0、実行可能JAR生成成功。
- 軽量CLI130件・ランチャー6件のverifyが成功。
- Checkstyle0、SpotBugs既存1,893・新規0、既存増分ゲートと自己試験8ケースが成功。
  基準・除外は変更していない。可変サービスをSchedulerのrecordから直接公開しない形へ整理した。
- 初回のサンドボックス内関連試験はMockito接続パイプを拒否されたため成功扱いにせず、
  今回の試験だけを停止して接続可能な環境で再実行した。残った子プロセスがないことも確認した。
