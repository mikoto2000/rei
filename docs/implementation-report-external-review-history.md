# External delegation: durable review history / explicit re-review

Branch: `codex/external-review-history`

既存read-only Codex adapterを維持し、SQLite開始/terminal記録、Project/root境界、reviewId、前回参照付きの明示再レビュー、CHAT履歴Toolを追加した。通常/re-reviewの認可とRunごと1回の予算・キャンセルは共通。raw log/prompt/CLI sessionは保存しない。保存失敗はfail closed、結果不明STARTEDは自動retryしない。

TDD Red: `target/external-review-history-red.log`（repository欠落）。新規テストは再起動後の結果/開始記録、redaction・サイズ・raw非保存、terminal不変性、Project/root境界、明示認可と共通予算、前回参照・現対象検査、保存障害、cancel保存、履歴ToolのJSONを検証する。新規8テストを含む関連テストが通過。全体回帰は2,800テスト / 538スイート、失敗・エラー・skipは0。Merge後にも関連テストを実行する。

詳細: [external-review-history.md](external-review-history.md)。実Codexプロセスは呼び出さずfake executorと既存adapterテストで検証する。fix/書込権限・CLI session resume・他agentの追加は今後の範囲。
