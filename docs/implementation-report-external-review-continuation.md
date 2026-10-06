# External review session continuation

Status: Implemented

Branch: `codex/external-review-session-continuation`

Commit / Merge: この独立branchのfeature commitとmainのmerge commitで記録。

Merged into: `main`

Implemented:
- Opt-inのnative CLI session保持と成功レビューのUUID保存。旧JSON・既存constructor・既定ephemeral動作は互換。
- 保存reviewIdを使ったCHAT Tool継続。現在の明示Codex依頼、Project/root/対象境界、既存Run予算・キャンセル・lifecycleイベントを再利用。
- exact UUIDのresume、CLI capability確認、read-only隔離設定、共有total timeout。失敗時に新規プロセスへfallbackしない。
- SQLiteの原子的parent claimで二重継続と障害後の再送を防止。成功した子reviewからの次回継続に対応。

Tests:
- Red: 新APIを参照するテストが未実装で失敗。
- Green: 既存external review関連テストと新規継続テストがPASS。
- Full: `-Pfull test`、3012 tests / 574 suites、failure/error/skipped各0、exit 0。
- SQLite restart・同時claim・旧JSON・失敗/不明状態、UUID不一致、隔離引数、非対応CLIと非opt-inを確認。
- ローカルCodex CLI 0.154.0のresume helpと実構築と同じ引数形式をread-onlyで確認。実アカウントのLLMレビューは実行せず、process portで検証。

Result: PASS

Remaining:
- 外部fix/書込委譲、複数provider・並列外部委譲は追加候補。
- CLI会話とRei DBの分散transactionは保証しない。障害後は自動継続せず新しい明示レビューで対応。
- 図編集、Document Agent、Paper Provider/E2E等の残件は別branchで継続。
