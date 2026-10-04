# 確認待ちと Escalation

所有 Project / Session / Run がある既存イベントから、SQLite の確認待ち一覧を作成します。
モデルの回答文・例外メッセージの内容を解釈して判断待ちと分類することはありません。

| Kind | 観測事実 | 次の操作 |
|---|---|---|
| APPROVAL_REQUIRED | PermissionGuard の PermissionRequired | /approval list で確認し、必要なら承認後に明示 Resume |
| POLICY_DENIED | PermissionGuard の PermissionDenied | Task と Policy を確認 |
| STAGNATION_STOPPED | Run の停滞停止 | 結果と checkpoint を確認 |
| GOAL_STOPPED | 永続GoalのBLOCKED / FAILED | /goal show と /goal history で条件・予算・試行を確認 |
| LONG_WAIT | 確認済み待機が観測上2分以上継続 | 管理プロセスと Task を確認 |

待機時間は monotonic clock を使います。確認済みの waiting_for_dependency を繰り返し観測した場合だけ通知し、
進捗・通常の停滞判定・Run終了で待機観測を解除します。追加 LLM や監視用のプロセス起動はありません。
イベントが来ない間の経過時間だけでは通知しません。再起動後は待機の連続時間を再計測します。
実行中の観測・終了 Run の遅延イベント除外はそれぞれ最大1024件保持します。

同じ Project / Session / Run / Kind / reference の通知は永続 unique key で一度だけ作成します。
別プロセスや再起動、確認済み操作の後に同じ事実が再通知されても、新しい項目を作りません。
別 Run は別の問題として記録します。確認項目は OPEN / ACKNOWLEDGED の状態を持ちます。
記録する通知文は固定文で、Tool引数・LLM出力・例外メッセージ・stack trace をコピーしません。

```text
/attention list
/attention show attention-ID
/attention ack attention-ID
```

現在の Shell Project の項目だけを表示・操作します。一覧は未確認の古い順で最大256件です。
ack は確認済みにするだけで、承認・Policy変更・キャンセル・Resume・Task実行を行いません。
モデル向けの確認済み Tool は公開しません。

既存Web認証下の API:
- GET /api/v1/projects/{projectId}/attention
- GET /api/v1/projects/{projectId}/attention/{id}
- POST /api/v1/projects/{projectId}/attention/{id}/ack

新規作成時は所有境界を引き継ぐ `attention.required` イベントを発行します。
payload は attentionId / kind / message。Shell は `[attention]` と show コマンドを表示し、
Web SSE の公開 field whitelist でもこれらを返します。STAGNATION_UPDATED の固定値
`reason=waiting_for_dependency` だけを追加公開し、任意の内部 reason と evidence は非公開のままです。
通知配送が失敗しても、永続一覧から確認できます。通知は実行権限の根拠にはなりません。

Native 専用の一覧/ボタン、OS push・音声・メール等の外部配送、任意の「判断待ち」分類、
全失敗の原因診断、Runをまたぐcooldown、解決事実との自動照合、履歴retention は未対応です。
Project移動や予約の不確定RUNNINGは /timer の状態確認に従います。
