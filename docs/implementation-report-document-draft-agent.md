# Document draft agent / diagram editing

Status: Implemented (existing UTF-8 text editing)

Branch: `codex/document-draft-agent`

Commit / Merge: 独立feature commitとmain merge commitで記録。

Merged into: `main`

Implemented:
- 既存SubAgentのdocument-editorサンプルと専用JSON Schema。文書・Markdown・Mermaid・PlantUMLの自然言語編集案、完全baseline/置換、出典・未検証事項を返す。
- 読み取り専用Tool、8step/120秒、最大1回のvalidation repair。既存の予算・キャンセル・Policyを再利用。
- 親による保存Change Set・差分確認・明示Apply/Discardの手順。子には書込・Shell・再帰委譲を与えない。
- サンプルcatalogの回帰を更新。ユーザー設定は自動変更しない。

Tests:
- Red: 未追加のdocument-editor定義を読む2テストが失敗。
- Green: 実定義/Schema、既存SubAgent runner/configuration、Text Change Set関連がPASS。
- Full: 3014 tests / 575 suites、failure/error/skipped各0、exit 0。
- 実SQLite/filesystemで図編集案→保存差分→再起動→明示適用、CRLF保持、stale source非上書き・Project外拒否を確認。

Result: PASS

Remaining:
- Live LLMによる編集内容の品質・図の構文/rendering・引用/事実の意味検証はこのテストの保証外。未検証事項として表示する。
- Binary文書生成、複数ファイルtransaction、専用rendererは追加候補。Paper Provider/E2Eは次の独立機能で対応。
