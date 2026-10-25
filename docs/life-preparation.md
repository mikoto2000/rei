# 先回りタスク準備

[生活制約](life-context.md)・[朝夕ブリーフィング](life-briefings.md)・[既存の秘書実行と承認](secretary.md)を拡張します。提案は任意であり、確定した義務や実行許可にはなりません。新しい汎用ジョブエンジンは使用しません。

## 候補と時間の扱い

既存のOrchestratorが、承認済み台帳の期限・Waiting・`after:TASK_ID`依存・重要度・停滞・既存Planning/WorkContextへの明示参照から候補を抽出します。予定や継続案件は既存接続の候補取り込み・利用者確認、または登録済みタスクの参照で結びます。未確認の会話抽出・外部候補は勝手に義務へ変換しません。未登録の予定や期限は作りません。

準備時間・負荷を登録できます。未知の所要時間をモデルで埋めません。

```text
/secretary add --project PROJECT --key trip-preparation --title "週末の外出の準備" --due 2026-10-30 --priority 2 --tag prep-minutes:30,load:LOW
/secretary orchestrator-configure --project PROJECT --proposals-enabled --minimum-seconds 900 --repeat-seconds 86400
/secretary suggest --project PROJECT
/secretary preparation --project PROJECT
/secretary suggestions --project PROJECT
```

`prep-minutes:N` は1〜10,080分の利用者登録値、`load:LOW` / `MEDIUM` / `HIGH` は任意です。重複・不正な時間はUNKNOWNとして扱います。期限は元のLocalDate精度を維持し、その日の終端から準備分を引いた着手目安、重要度、時間の確実性、負荷、更新日時で安定して並べます。長い準備は期日前から候補化します。日付から時刻付きの新しい期限を確定する意味ではありません。

未完了・所在不明の `after:` 依存を持つ作業は実行可能候補にしません。Waitingは確認の提案として扱い、未完了の前提を飛び越して実行しません。

今すぐの作業候補には、明示的なAVAILABLEと既知の準備時間が必要です。勤務・休息・例外・保存済みの私用予定で継続利用可能時間を短くし、その半分かつ最大60分、最大3件まで選びます。残り時間を全て埋めません。UNKNOWNの時間や所要時間は「実施可能」と扱いません。勤務中の業務予定は取得・推測しません。

保存済みCalendarは既存の明示有効接続だけを参照し、新たな通信をしません。現在の既知予定はBUSY、未来予定は候補時間の上限にします。空のCalendarだけではAVAILABLEになりません。キャッシュの読取・時刻解釈に失敗した場合はUNKNOWNとして候補を止めます。キャッシュは古い場合があり、外部の最新状態の保証ではありません。更新は従来の接続pollとその許可範囲に従います。

## 過剰提案と履歴

Projectの実行前アクティブ提案は最大3件です。同じタスクの提案を重複させず、既存の通知間隔・HELD/DIGESTも維持します。PROPOSED等は更新後7日でEXPIRED、元タスクが完了・取消・変更されればINVALIDATEDです。延期は指定した延期日時の後まで有効期間を延ばします。失効した同一ルール・同一タスクrevisionは、毎日再発行しません。期限超過など別の根拠や利用者によるタスク更新は新しい判断対象です。

```text
/secretary feedback SUGGESTION_ID --project PROJECT --revision REV --reaction LATER --delay-seconds 86400
/secretary feedback SUGGESTION_ID --project PROJECT --revision REV --reaction DISMISS
/secretary feedback SUGGESTION_ID --project PROJECT --revision REV --reaction NEVER
/secretary suggestion-history SUGGESTION_ID --project PROJECT
```

DISMISSは少なくとも7日（長いrepeat設定はその値）空け、日付によるルール変更にも反映します。NEVERは既存どおりタスク全体を抑制します。LATERの期限も別ルールで回避しません。期限・元revisionは自動取り込みと実行開始時にも再確認するため、古い承認や提案は実行根拠になりません。通知が保留中でも不要な提案は配送時に抑止されます。

## 自動準備と承認

既存Autonomyの明示有効化・実行Session・Policy・ToolPermissionGuard・予算が必要です。自動取り込みにはさらに `rei.secretary.autonomy-enabled=true` とProjectのauto-prepareが必要です。既定は無効です。

```text
/secretary autonomy-configure --project PROJECT --session SESSION --autonomy-enabled --max-level 1 --auto-prepare --auto-kind PREPARE --max-concurrent 1 --max-seconds 300 --max-llm-calls 8 --max-daily-calls 32 --max-retries 1
/secretary work-list --project PROJECT
/secretary work-show JOB_ID --project PROJECT
/secretary work-mode JOB_ID --project PROJECT
/secretary work-history JOB_ID --project PROJECT
```

勤務中の準備には生活設定のbackgroundにそのProject・taskの正確な組が必要です。接続の自動更新は別途 `connection:ID` の許可が必要です。休息中は開始せず、既存の動作中処理も既存の停止・予算予約境界で再評価します。通常チャットや別のRunに実行許可を流用しません。

表示上は次の4状態を区別します。

| mode | 意味 |
| --- | --- |
| PROPOSAL_ONLY | 提案のみ、または開始条件が整っていない |
| AUTO_PREPARATION | 既存Policyで許可されたREAD_ONLYの調査・要約・準備 |
| APPROVED_SCOPE | 既存の消費済み承認・明示Policyの範囲に結び付いた実行 |
| APPROVAL_REQUIRED | 既存の承認を待つ実行 |

分類は表示情報です。再開・開始可能性や現在の承認を保証せず、開始・dispatch・モデル呼出には従来の再検証を適用します。下書き保存は既存DRAFTの権限に従います。外部送信、支払、予約、変更・削除、重要設定変更の承認条件は緩めません。定期調査や継続計画は既存Goal/ROUTINEと既存の許可・再開手順を使用します。

APIはBearer認証下の `GET /api/v1/secretary/preparation?projectId=PROJECT`、`GET /api/v1/secretary/executions/JOB_ID/mode?projectId=PROJECT` です。実行操作は従来のexecutions APIです。CLIとAPIはProject境界を共有します。

## ブリーフィング・保存・確認

有効な提案は `secretary-suggestion`、準備結果は既存の `secretary-execution` として朝夕に統合します。提案はPROPOSAL、準備時間の元値は利用者登録情報と区別し、タスク・提案・Jobを追跡できます。同じsource・IDはPhase 2の集約で重複しません。下書きや調査要約は確認済み事実として扱いません。

状態・拒否・延期・失効の履歴は既存storage.dbのsuggestions/historyに保存し、新しい台帳は作りません。既存の提案・実行履歴削除・バックアップ管理方針に従います。朝夕スナップショットの削除は `briefing-clear` です。元タスクは既存cancel、提案抑制はNEVERを使います。これらは監査履歴の物理削除ではありません。

確認: 期限・準備時間が違うタスク、未完了依存、所要時間不明を登録し、順序・候補除外・時間上限を確認します。Clock固定テストで7日経過、拒否後の日付変更、30日延期、元タスク更新、再起動、勤務/休息/私用予定境界を確認します。権限拒否・予算不足・外部/モデル障害は既存Autonomyの試験と合わせて検証します。実外部アカウントや実デスクトップ機器への到達性は別途の動作確認が必要です。
