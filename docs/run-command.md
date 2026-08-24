# runCommand

## 目的

`runCommand` は LLM に公開する唯一の Shell command 実行入口である。既定の `auto` はコマンド内容を解析せず、実際の Process が3秒以内に終了したかだけで foreground/background を解決する。旧 foreground/background メソッドは内部互換コードとして残すが、Tool definition には公開しない。

## 入力

```json
{
  "command": "npm run dev",
  "executionMode": "auto",
  "timeoutSeconds": 30
}
```

- `command`: 必須。空白不可。
- `executionMode`: `auto`（既定）、`foreground`、`background`。大文字小文字は区別しない。
- `timeoutSeconds`: 明示 `foreground` の timeout。null は既存既定30秒、1～600秒へ既存処理で正規化する。auto の観察時間とは別概念。

working directory は既存 Tool と同じ現在の project directory を使用する。既存 primitive に environment map がないため、Phase 2 では追加していない。

## 出力

```json
{
  "status": "completed",
  "executionMode": "auto",
  "resolvedExecutionMode": "foreground",
  "exitCode": 0,
  "stdout": "...",
  "stderr": "",
  "processId": null,
  "pid": null,
  "timedOut": false,
  "errorMessage": null
}
```

`status` は `completed`、`running`、`failed`。background 解決時は既存 logical `processId` と OS `pid` を返し、`getShellProcessStatus` と `killShellProcess` でそのまま操作できる。

## モード

- `foreground`: 既存の同期実行ロジックを再利用する。終了または timeout まで待ち、timeout 時は既存どおり強制終了する。
- `background`: 既存 `BackgroundProcessManager.spawnShell` を再利用し、待たずに管理 ID を返す。
- `auto`: 最初から既存 Manager に一つの Process を登録して stdout/stderr reader と watcher を開始する。同じ Process を3秒だけ待ち、終了済みなら reader をdrainして通常結果へ変換し registry から除去する。生存中なら登録を残して background 結果を返す。

## race と出力

auto の wait が timeout した直後に `Process.isAlive()` を再確認する。終了していれば foreground として回収し、生存していれば processId を保持する。background 返却直後に終了しても registry には終了状態が残るため processId は status 取得に使用できる。

auto/background の stdout/stderr は既存 `BoundedLineBuffer` を共有し、各stream最大200行を保持する。runCommand の即時結果は既存既定の末尾100行を返し、その後も status Tool から最大200行まで読める。foreground は既存同期 Tool の文字列取得仕様を踏襲する。

Process 起動、Shell 解決、PowerShell/cmd/bash の command line、working directory、status、kill、子Process tree停止はすべて既存実装を再利用する。外側の `runCommand` Tool callback だけが Tool event を発行し、内部遷移で別 Tool call は発行しない。

## スコープ外

command名 heuristic、LLM分類、restart/supervision、永続化、再起動後の復元、Docker/SSH、PTY刷新、対話CLI、environment map追加、workflow DSL は対象外。
