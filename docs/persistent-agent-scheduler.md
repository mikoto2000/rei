# 永続する一回限り continuation

既存の `scheduleAfter` / `scheduleAt` は、メモリだけの登録から SQLite への永続登録に変わりました。
Reminder・Feed・Topic の個別 timer はそのままです。繰り返しスケジュールは追加しません。

## 登録・有効化

Chat の現在の AgentRunScope にある Project、絶対パス、Session を保存します。
モデル指定の conversationId が現在の Session と異なる場合、所有 Project がない場合、
過去の日時、366日を超える期限、空/4096文字を超える action は拒否します。
Project あたり未終了予約は最大256件です。

新規予約は PENDING です。登録しただけでは実行しません。
人間の Shell 操作で確認して有効化します。モデルに activation Tool は公開しません。

```text
/timer list
/timer show timer-ID
/timer activate timer-ID
/timer history timer-ID
/timer cancel timer-ID
```

全操作は現在選択した Project に限定します。cancel は未取得の PENDING / SCHEDULED だけです。
表示と保存する outcome は資格情報を redaction します。action 自体は再実行に必要なため DB に保存します。
登録後の action・Project・Session・日時の変更は提供しません。変更する場合は cancel して新規登録します。

## Dispatch

```properties
rei.agent-scheduler.enabled=true
rei.tool-permission.enabled=true
```

自動 dispatch は既定で無効です。両方の設定が有効な場合だけ、既定5秒間隔で期限を確認します。
`rei.agent-scheduler.check-interval-ms` で間隔を設定できます。
予約日時は wall clock の絶対時刻です。ポーリングで追加 LLM を呼び出しません。

期限に達した SCHEDULED を単一 SQL writer 操作で RUNNING にし、新しい runId を保存してから実行します。
履歴更新は同じ transaction 内です。同じ Project/Session の同時 claim は DB index で禁止します。
1プロセス内の Scheduler dispatch は同時1件。期限順で既存 Project FIFO に admission します。
現在の Shell の Project・Session 選択は使いません。起動時とキュー実行開始時に、登録Projectの
存在・パス一致・Session 所有を検証します。削除/移動した Project を別の場所で実行しません。

ChatExecutionService の既存 Tool permission、承認、LLM 上限、停滞・checkpoint の境界を利用します。
実行中に Tool 承認が必要なら従来どおり停止し、ユーザーが承認・明示 Resume します。
予約の有効化は将来の Tool 引数への一括承認ではありません。
通常の Chat 実行イベントを Web SSE / Shell projection で観測できます。

Chat の実際の結果に応じて COMPLETED / FAILED / CANCELLED を記録します。
成功結果・エラーの保存は redaction 後512文字までです。失敗やキャンセルを自動再試行しません。

## 再起動と残る範囲

PENDING / SCHEDULED と結果履歴は再起動後も残ります。期限超過の SCHEDULED は設定有効時に取得します。
RUNNING の予約は実行成否が不明なので自動で SCHEDULED に戻しません。
外部副作用の exactly-once を保証する仕組みではなく、自動 dispatch の at-most-once claim です。
RUNNING は永続状態表示で確認します。成否の照合・予約復旧の専用操作は未実装です。
その Session の別予約も、未解決 RUNNING がある間は claim しません。

実行は既存 operation FIFO を使用し、専用の介入 mailbox・Native 予約UI・Web予約管理APIは未対応です。
cron/反復、汎用ファイル/Git/API trigger、複数アプリ間の Project FIFO、履歴の自動 retention も未対応です。
DB は既存 memoryConsolidationDataSource を利用し、新しい外部サービスは必要ありません。
