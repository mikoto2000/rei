# 管理対象プロセス待機の実装報告

Status: Implemented (managed-process slice)

Branch: `codex/process-dependency-waiting`

Commit / Merge: `feat: wait for managed process dependencies` / `Merge branch codex/process-dependency-waiting` を参照。

Merged into: `main`

Implemented:
- 既存 logical processId を観測する `waitForShellProcess`。専用 adapter と独立した domain awaiter に分離。
- 既定10秒、最大60秒、200ms polling。monotonic clock、キャンセル伝播、追加 LLM 呼び出しなし。
- COMPLETED / FAILED / CANCELLED / BLOCKED / WAITING を区別。ログの成功主張を完了判定に使わない。
- 確認済み待機だけの反復では停滞を増やさず、進捗を捏造せず、既存カウントと LLM 上限を維持。
- terminal state の変化を一度だけ進捗として記録。別の停滞操作が混在した場合は従来の判定。
- Shell の待機表示と既存 Web SSE payload。Policy は READ、既存 checkpoint の結果保存経路を利用。

Tests:
- Domain の時間制御、期限、完了、キャンセル、上限検証。
- 管理プロセスの終了コード・強制終了・不明 ID の分類、起動/終了副作用がないこと。
- 実際の Spring AI Tool callback で ToolContext を schema から除外し、確認済み待機を Run に反映。
- 待機6反復、混合反復、terminal 重複、LLM 上限、Shell 表示の回帰。
- 全 Java: 2,700 tests / 519 suites、failure=0 / error=0 / skipped=0。
- JDK25、既存 cache、`mvnw.cmd -o -Dmaven.repo.local=F:\project\rei\.m2\repository -Pfull test -q`。
- diff review / `git diff --check` PASS。

Result: PASS

Remaining:
- 汎用 dependency graph、ファイル/Git/API watcher、ユーザー回答待ち。
- Native の専用待機表示、watcher の永続化・再起動復元・自動 Run 再開。
- 管理プロセスへの再接続は既存 BackgroundProcessManager の範囲に従う。
