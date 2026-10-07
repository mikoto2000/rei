# 永続 Goal と bounded Planning Loop

現在の実装は、指定した Project-relative file の SHA-256 を完了条件とする Goal です。
既存 ActionPlan / TaskState / Chat の Tool loop を再利用し、その外側で独立検証と継続判断を行います。
モデルの回答、TaskState.COMPLETED、ActionPlan.DONE は Goal の完了証明にはしません。
COMPLETED は設定したファイル条件を観測時に検証したという意味で、任意の目的の意味的な完了を保証しません。

## Shell

Project と既存 Session を選択してから登録します。
objective は生成すべき内容・作業を具体的に指定し、期待SHA-256はユーザーが指定します。

```text
/goal create "Produce the requested exact artifact" --file output.txt --sha256 <64 hex characters> --max-runs 3 --max-llm-calls 20
/goal show goal-ID
/goal run goal-ID
/goal verify goal-ID
/goal history goal-ID
/goal cancel goal-ID
/goal list
```

登録だけでは実行しません。モデル向けの Goal作成・起動・完了設定 Tool は公開しません。
run はユーザーの明示操作で、`rei.tool-permission.enabled=true` が必要です。
現在の Shell Project だけを参照・操作します。作成時の Project ID、絶対root、Session、目的、
条件、上限は固定です。Projectあたり未終了Goalは最大256件です。
保存した目的は再実行に必要なためDBに保持します。Shell表示は資格情報をredactionします。

## 実行サイクルと予算

1. 保存した Project/root/Session の所有境界とファイル条件を確認。
2. 原子的claimを保存。同じProject/SessionのGoalは同時に1件だけRUNNING。
3. Run番号・新しいrunIdを保存してから、既存Project FIFOへadmission。
4. キュー実行直前に所有を再確認し、既存Chat/ActionPlan/TaskStateで次の作業を実行。
5. ファイル条件を独立検証。一致した場合だけCOMPLETED。
6. Chat成功でも未検証なら、残予算内で次のRunへ継続。失敗・拒否・承認待ち・キャンセルでは停止。

Run上限は1..10、既定3。Goal配下の制御対象Chat LLM呼び出し上限は1..100、既定20です。
各Chatの既存Run上限と出力分割・停滞再計画の上限も維持します。
モデル呼び出し前にSQLiteの単一更新で予約し、進捗・再開・再起動で予算を補充しません。
失敗した呼び出しや実行成否が不明な予約の予算も戻しません。
Goal内のdelegateTask / delegateTasksは、子の初回呼び出し・Tool cycle・validation repairも同じ永続LLM予約から消費します。後続作業でSkill selectorと通常Chatの子にも共通呼出回数を適用し、[Run報告token上限](shared-run-token-limit.md)と[Goal永続報告token上限](persistent-goal-token-limit.md)を追加しました。独立Sleep／要約／CLI／embedding/rerankへの予算継承は対象外です。

承認要求はWAITING_APPROVAL、Policy拒否や予算・検証上の停止はBLOCKED、実行失敗はFAILED、
RunキャンセルはPAUSEDです。ユーザーは原因を確認して明示的にrunできますが、累積上限は維持します。
承認が必要な場合は既存 `/approval` で決定します。次のGoal Runでも引数の完全一致条件を適用します。
Goalのrunは既存checkpointの復元操作とは別です。checkpointを明示Resumeした場合は、
Goalの条件を `/goal verify` で再確認してください。

## 独立検証とキャンセル

ファイルはProject内の相対パスだけです。絶対パス、親 traversal、Windows ADS、symbolic link、
通常ファイル以外、1MiB超は拒否します。SHA-256の計算は読み取りだけで、プロセスを起動しません。
ファイルの内容を通知や検証結果へコピーせず、検証理由の固定値を返します。
検証はその時点の観測であり、外部の同時変更や完了後のファイル変更を恒久的に防止する機能ではありません。

条件が実行前に満たされている場合、LLMを呼ばずに完了できます。
verifyは読み取り検証を行い、未実行・停止中Goalなら条件成立時に完了を保存します。
RUNNING / CANCELLED の状態はverifyで変更しません。完了済みGoalは履歴として維持します。

cancelはGoalのclaimと以後のLLM予約を無効にし、未開始のキューを除外し、登録済みRunへキャンセルを伝えます。
遅延した結果でGoalを完了へ上書きしません。外部副作用のrollbackを意味する操作ではありません。

## 保存・表示・残る範囲

SQLiteにGoal、試行、状態履歴、累積予算を保存します。モデル回答は既存会話記録を利用します。
`goal.updated` のShell表示は状態・Run数・使用LLM数を示します。BLOCKED/FAILEDは既存attention inboxにも記録します。
公開event mapperは状態と予算だけを投影し、内部reasonを公開しません。

再起動時の自動Run開始やRUNNINGの自動再試行は行いません。RUNNINGは副作用の成否が不明なので、
保存状態と実行証跡を確認したうえで、Shellの `/goal reconcile` を利用できます。詳細は goal-uncertain-run-recovery.md を参照してください。
Goal専用HTTP操作とNativeの確認／起動／取消／照合は、後続の[HTTP統合](implementation-report-goal-http-controls.md)・[Native統合](implementation-report-native-goal-controls.md)で対応しました。
汎用のbuild/test/API条件、独立Sleep／要約／CLI／embedding/rerankへの予算継承、Goal専用介入mailbox、
複数アプリ間のProject FIFO、Goalとcheckpointの自動関連付けは追加候補です。

## 定義した追加完了条件

Goal definitionにcompletion evidence、required tests/artifacts/predicates、review gateを保存できる。
FileGoalVerifier/GoalLoopの全完了経路で追加証拠を再確認し、旧ファイル判定も維持する。
全Goalに人の定義を強制する設定は既定OFF。人の定義変更、捕捉Goal Runの証拠添付、
strict JSON/HTTP/SQLite migrationと状態は[Goal completion gate](goal-completion-gate.md)を参照。
