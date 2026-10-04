# 管理対象プロセスの待機

Chat の `waitForShellProcess(processId, timeoutSeconds)` は、既存 `runCommand` の logical processId を観測して終了を待ちます。
新しいプロセスの起動・終了・再接続は行いません。Policy 分類は READ です。

待機は既定10秒、最大60秒、200ms間隔。0秒では状態を一度確認して返します。
monotonic clock と Sleeper はテストで差し替えられます。cancellation は各観測前後と sleep の interrupt で伝播します。
待機のための追加 LLM 呼び出しはありません。

戻り値 `ShellDependencyResult` は `dependency`（id/state/detail）と既存 `BackgroundProcessSnapshot` を含みます。

| Process | Dependency |
|---|---|
| STARTING / RUNNING（待機期限到達） | WAITING |
| EXITED、exitCode=0 | COMPLETED |
| EXITED、非0または未取得 / FAILED | FAILED |
| KILLED | CANCELLED |
| logical processId が見つからない | BLOCKED |

RUNNING は polling 中の domain 状態です。WAITING は成功ではなく、まだプロセスが終わっていないことを示します。
stdout/stderr に完了を主張する文字列があっても state を変えません。

## Stagnation との統合

Tools が実際に取得した typed observation を RunExecutionContext へ渡します。
成功した Tool 呼び出しすべてがこの確認済み待機であり、他の進捗・失敗がない反復だけは停滞カウントを増やしません。
待機を進捗と偽って記録せず、既存カウントを0に戻しません。別の進まない操作が混ざった反復は従来の停滞判定を行います。
同じ dependency の terminal state 変化は一度だけ確認済み情報として扱い、同じ完了状態の再取得は新しい進捗になりません。

既存 `stagnation.updated` に `reason=waiting_for_dependency` を載せ、Shell は `[waiting] awaiting dependency` を表示します。
Web SSE でも同じ payload を取得できます。新しい event schema や追加 LLM は不要です。
Run の LLM 呼び出し上限・cancellation・Tool permission・checkpoint boundary は維持します。
最大64 dependency の直近状態を Run 内に保持します。Tool の戻り値は既存の結果保存・checkpoint reference の経路を利用します。

## 残る範囲

汎用 dependency graph、ファイル/Git/API の watcher、ユーザー回答待ち、WAITING の専用 Native 表示、
再起動後の watcher 復元・自動 Run 起動は未実装です。BackgroundProcessManager の既存管理 ID を観測し、
再起動で再接続できないものを未実行と推測して再起動することはありません。
