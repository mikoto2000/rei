# 長時間タスク中の相談と追加指示

`rei.conversation.concurrent-enabled=true` で明示的に有効にする。既定false。
従来の投稿は `EXCLUSIVE` のまま。同じProjectの変更処理は順番に実行する。

## 入力の選択

Nativeの送信方法で「作業」「相談」「読み取り」「実行中Runへの追加指示」を選ぶ。
相談と読み取りは、同じSessionへ新しいUser Turnと別Runを作る。
追加指示は対象Runを選び、既存Runのmailboxへ入れる。新しいRunを作らない。
RunカードにモードとRun IDを表示する。個別停止は選んだRunだけに作用する。
Session終了は会話の選択・活動を終了する操作で、Run全体取消ではない。

Shell:

```text
chat --mode CONVERSATION 今のタスクとは別に相談したい
chat --mode READ_ONLY 調査してください
chat --run <run-id> このRunへの追加指示
```

HTTPの `POST /api/v1/chat` は任意の `mode` を受け付ける:

```json
{"projectId":"<project-id>","sessionId":"<session-id>","message":"別の相談","mode":"CONVERSATION"}
```

`POST /api/v1/runs/<run-id>/input` はProjectとSessionを必須とする:

```json
{"projectId":"<project-id>","sessionId":"<session-id>","message":"このRunへの追加指示"}
```

正常受付は202。異なる所有権・未知Runは404、非実行中・終了済みmailbox・容量到達は409。
空入力・16,384文字超過は400。mailboxは32件まで。追加指示も既存Runの予算を使う。
無効設定での制限付きRun受付は400、追加指示APIは409。

## 実行と安全境界

- `EXCLUSIVE`: 同じProjectの書き込み・読み取りRunと排他。待機する。
- `READ_ONLY`: 既知の読み取りToolだけ。READ同士は並行実行できる。
  待機中WRITEの後から来たREADは先行しない。
- `CONVERSATION`: Toolを使わず、Projectの長時間タスク中も独立して回答する。

同時実行は全体64、受付は同Project64・全体256。実行中WRITEの取消はcleanupまで排他枠を保つ。
未知/MCP/外部委譲/任意コマンドはREADと扱わない。権限設定が無効でもモードによる拒否を維持する。
制限付きRunはファイル・clipboard添付を展開しない。必要なProject内の読み取りはREAD_ONLY Toolで行う。
通常Runの既存Tool許可・Project/root境界は維持する。

相談・読み取りは、完了済み最大12ターン・48,000文字の履歴を開始時に固定する。
実行中・取消・失敗ターンを相談の会話履歴へ混入させない。既存の取消指示と再開参照は別Advisorで保持する。
共有ChatMemoryを更新するAdvisorや作業自動更新を使わず、会話ログとTurnはRunごとに記録する。
Project/Conversationの作業状態はRun専用インスタンスとし、終了時に解放する。
Working Setは制限付きRunから既存Sessionの保存ファイルへ書き込まない。

`rei.conversation.timeout` はISO-8601 Duration、既定 `PT5M`、正数かつ最大 `PT1H`。
相談・読み取りRunの全実行に対する期限で、Tool/model反復ごとにリセットしない。
期限切れはFAILED/RunTimeout。ストリームとmailboxを閉じ、ユーザー取消と区別する。
既存のRun/Goalモデル回数・token予算と個別cancelも維持する。

## 再接続・再起動

有効化時だけ、既存SQLite DataSourceに `rei_run_registry` を追加する。
保存するのはRun ID・Session・Project/root・source・mode・状態・時刻・失敗情報と実行プロセスの所有権。
会話本文・生Tool結果・認証情報はこのtableに保存しない。
状態変更は保存内容の一致を条件に更新し、別の生存中プロセスのRunを操作しない。
プロセス識別はPIDと開始時刻の両方を確認する。

終了時または実行プロセス消失後、途中RunをUNKNOWNとして保存・復元する。
受付済みRunを自動再実行しない。Nativeは結果不明を表示する。
UNKNOWN、または再起動で終端イベント履歴を失ったRunのSSEは409 replay gapとなり、既存Native再接続処理が状態取得に切り替える。
終端Runの状態保存は既存の30分retentionに従う。Taskの長期復元は既存Checkpointを使う。
受付失敗は永続Runもrollbackする。

Checkpointにはモードを追加保存し、旧形式はEXCLUSIVEとして読める。
相談・読み取りの開始で既存Action Plan/Task Stateをリセットしない。
明示的なCheckpoint再開でもモードを保持し、元のRunとのlineageを維持する。
未知の副作用・過去の承認を自動引き継ぎして再実行しない。

Task IDは既存Checkpoint taskId、Run IDは一回の実行、Session IDは会話を識別する。
再開は同じTaskに新しいRunを作る。Task Managerの関連表示はTask projection実装で統合する。

## 検証

ProjectRunQueue、RunRegistry、ChatExecutionService、ToolPermissionGuard、SessionLifecycleを再利用した。
TDDのRed/Greenは受付モード、READ/WRITE競合、取消、個別mailbox、HTTP所有権、
Tool制限、共有状態分離、履歴固定、期限切れ、再起動とNative/React入力で確認する。
再起動fixtureは別JVMを強制終了し、別JVMからUNKNOWN/所有権/READ_ONLY復元を確認する。
有料モデル・外部サービス書き込みは実行しない。全体回帰とGit receiptは
[全件実装レポート](implementation-report-remaining-features-2026-10-07.md)へ記録する。
