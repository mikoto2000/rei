# Auto Sleep

Status: Implemented

Branch: `codex/auto-sleep`

Merged into: `main`（機能コミット・merge hash は Git 履歴の `feat: add opt-in idle auto sleep` を参照）

Implemented:
- 既定無効、idle・未処理件数・Agent/background busy による実行条件。
- terminal turn 保存後の Session 登録、専用 worker、1 batch ずつの実行、試行間隔。
- 入力再開時の interrupt と transaction 前後の cancellation guard。
- Manual Sleep のカーソル・重複防止・監査履歴・イベントの再利用。
- 設定と再起動・観測範囲の制約を long-term-memory.md に記録。

Tests:
- Red: AutoSleepServiceTest の追加後、未実装クラスで compile failure を確認。
- Green: AutoSleepServiceTest 8 件。関連 SleepServiceTest の抽出中 cancellation を追加。
- 関連4クラス（AutoSleepServiceTest / SleepServiceTest / MemoryBoundaryTest / MemoryCliTest）PASS。
- 全 Java suite: `mvnw.cmd -o -Dmaven.repo.local=F:\project\rei\.m2\repository -Pfull test -q`。
- JDK 25、既存 integration の localhost / native extension / 保存先へアクセスできる条件。
- 2,672 tests / 513 suites、failure=0 / error=0 / skipped=0。
- `git diff --check` と手動 diff review PASS。

Result: PASS

Remaining:
- 起動時保存metadataのbounded走査は後続の auto-sleep-startup-discovery で対応。cron、日次、Session 終了 trigger は未対応。
- OS 全体の idle と入力途中のキー操作は観測しない。
- 候補は最大256件。永続Sleep cursorを保持し、起動時の保存metadataと会話終了後の登録を利用する。
