# Goal の事実に基づく Reflection

既存 Sleep の記憶統合、Work Context の決定・障害整理はそのまま利用します。
今回のReflectionは、永続Goalの期待条件と保存された検証結果を比較する独立した記録です。
追加LLM呼び出し、Goal実行、ファイル再検証、Memoryの書き込み・自動昇格は行いません。

GoalがCOMPLETED / BLOCKED / FAILED / PAUSED / CANCELLED / WAITING_APPROVALになった
`goal.updated` を観測して、SQLiteへ以下を保存します。

- Project / Session / Goal / source Run とsource eventの参照。
- 期待する相対ファイルとSHA-256。
- 実際の検証区分、保存された検証理由、期待との差分。
- 固定ルールによる次の確認操作。原因や教訓の確定ではなく、レビュー提案です。

| Actual | 保存された事実 | Gap |
|---|---|---|
| VERIFIED | Goalの独立ファイル検証が成立 | CRITERION_SATISFIED |
| UNVERIFIED | source試行でdigest不一致/通常ファイル条件不成立を観測 | CRITERION_NOT_SATISFIED |
| REJECTED | size/path/symbolic link等の検証制限で拒否 | VERIFICATION_REJECTED |
| NOT_CHECKED | 検証結果が未記録、承認待ち、実行失敗、検証不可など | VERIFICATION_NOT_RECORDED |

遅延イベントはsource Runと試行番号を照合します。後の試行が成功しても、以前の承認待ちを検証済みとは記録しません。
COMPLETEDはファイル条件の観測上の成立であり、任意の目的の意味的な成功や手順の正しさを意味しません。
目的文、モデル回答、Tool引数、例外文、ファイル内容はReflectionへコピーしません。

次の確認操作はREVIEW_APPROVAL、REVIEW_GOAL_BUDGET、REVIEW_BLOCKER、INSPECT_VERIFICATION、
INSPECT_EXECUTION_FAILURE、RECONCILE_STOPPED_RUN、REVIEW_VERIFIED_RESULTの固定値です。
どの提案も承認・予算補充・再試行・Memory採用を自動で行う根拠にはなりません。

```text
/reflection list
/reflection show reflection-ID
/reflection collect goal-ID
```

現在のShell Projectだけを表示・操作します。一覧は新しい順で最大256件です。
同じProject / Goal / source Run / Goal状態は一度だけ記録し、再起動や重複イベントでも増殖しません。
記録は上書きせず、createdAtとsource event参照を維持します。
createdAtは記録作成時刻です。sourceEventが`manual-collect:...`の場合は明示collect操作の参照で、
配送されたイベントIDではありません。collectで元のファイル検証時刻を再観測したとは記録しません。

collectは停止・完了済みGoalの現在の保存状態から記録する明示的なbackfillです。
通知を見逃した場合や再起動後にも利用できます。READY / RUNNINGは拒否します。
古い未配送イベントの全履歴を推測して復元する操作ではありません。

一般のTask/ChatのReflection、意味的な原因分析、ユーザー評価、validated memoryへの昇格、
Reflectionから次の計画への自動フィードバック、専用Web/Native UI、retentionは残作業です。
