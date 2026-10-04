# 永続 Scheduler / 一回限り continuation

Status: Implemented (one-shot continuation slice)

Branch: `codex/persistent-scheduler`

Commit / Merge: `feat: persist and dispatch one-shot agent schedules` / `Merge branch codex/persistent-scheduler` を参照。

Merged into: `main`

Implemented:
- 既存 scheduleAfter / scheduleAt を SQLite 保存へ移行。InMemory 実装は compatibility fixture として保持。
- 現在の Project / 絶対 root / Session を固定。最大366日、4096文字、Projectあたり未終了256件。
- PENDING を人間の /timer activate で SCHEDULED にする。モデルに activation Tool を公開しない。
- default-disabled dispatcher。Tool permission が有効な場合だけ claim・dispatch。
- 単一 SQL と transaction history、同じ Project/Session の二重 RUNNING 禁止。
- 新しい runId と既存 Project FIFO を利用。admission・実行直前に Project/root/Session 所有を再確認。
- 実際の Chat 結果を COMPLETED / FAILED / CANCELLED と履歴に保存。失敗・不明実行を自動再試行しない。
- Shell の list/show/activate/cancel/history、CHAT の両 client 登録経路、Policy classification。

Tests:
- Red: 永続 repository 未実装による compile failure を確認。
- Green: 再起動、明示 activation、所有境界・過去日時拒否、cancel、同時 claim、実行不確定 Run の非再実行。
- default-disabled / policy-disabled 非実行、bounded dispatch、成功・失敗・キャンセル、Shell controls。
- 実際の gateway で既存 FIFO の先行 Run と直列化し、固定 Project / Session / root で Chat を実行すること。
- 全 Java: 2,708 tests / 521 suites、failure=0 / error=0 / skipped=0。
- JDK25、既存 cache、`mvnw.cmd -o -Dmaven.repo.local=F:\project\rei\.m2\repository -Pfull test -q`。
- diff review / `git diff --check` PASS。

Result: PASS

Remaining:
- cron/反復、ファイル/Git/API trigger、Native UI・Web予約管理API。
- 不確定RUNNINGのShell復旧は後続scheduler-uncertain-run-recoveryで対応。自動副作用照合、複数アプリ間のProject FIFO、履歴retentionは未対応。
- 予約 Run の専用介入 mailbox。既存 operation FIFO と Chat cancellation/checkpoint は利用する。
- 新しい外部接続・サービスは不要。自動実行の設定は既定無効のまま。
