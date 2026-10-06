# External review fix proposals

Status: Implemented (read-only proposal / explicit local Apply / later re-review)

Branch: `codex/external-review-fix-proposals`

Commit / Merge: 独立feature commitとmain merge commitで記録。

Merged into: `main`

Implemented:
- 成功した保存レビューを参照するCodexのread-only単一ファイル修正案。専用prompt/schema、完全UTF-8 baseline/置換を取得。
- 現在の明示修正案依頼・Project/root/元対象範囲・共有外部委譲予算・キャンセル・不完全出力を検査。
- exact baselineと既存Change Set serviceでPROPOSED保存し、ファイル変更は行わない。結果はreviewId/changeSetIdだけで本文を重複保存しない。
- 共通diff/明示Apply後、別Runの新規再レビューへ接続。CLIのwrite権限・slash構文・子SubAgent権限は拡張しない。

Tests:
- Red: 新API/修正案result fieldsが未実装で失敗。
- Green: External review/process/authorization/Policy/advisorとText Change Set関連がPASS。
- Full: 3021 tests / 577 suites、failure/error/skipped各0、exit 0。
- 実SQLite/filesystemでレビュー→保存提案（非書込）→diff→明示Apply→別Run再レビューを検証。
- 通常レビューだけでの修正案認可・foreign Project・対象外・stale baseline・キャンセル後のfake success・不正proposal/CLI schemaを検証。

Result: PASS

Remaining:
- 外部CLIの直接書込/commit/push、複数ファイル修正、他provider/外部並列は追加候補。
- 修正内容の意味的正しさ・live LLM品質は保証せず、親の独立確認と実テストを行う。
- Review DBとChange Set保存は分散transactionではない。結果保存障害/不明状態で自動Apply/retryしない。
