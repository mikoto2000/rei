# Paper Provider / workflow validation

Status: Implemented (offline integration)

Branch: `codex/paper-provider-workflow-validation`

Commit / Merge: 独立feature commitとmain merge commitで記録。

Merged into: `main`

Implemented:
- OpenAlex/Crossrefが条件外・過剰な結果を返した場合、年範囲・公開状態・件数を保存前に再検査。
- 条件に必要な年/公開状態が不明なら適合と推測せず、fallbackでも同じ条件を維持。
- Provider parser→検索→実SQLite→Session参照→Library→Abstract-only要約・exact引用→再起動/要約cacheの結合テスト。
- 不正primary応答からのCrossref fallbackと、Sessionを跨ぐ番号参照の拒否を確認。

Tests:
- Red: 条件外/過剰なProvider応答が保存され、2テストで期待結果と不一致。
- Green: Paper関連79テストPASS。既存fallback fixtureに要求年のmetadataを追加し、意図した回帰条件を維持。
- Full: 3016 tests / 576 suites、failure/error/skipped各0、exit 0。

Result: PASS

Remaining:
- HTTP/LLMだけをfixtureに置換したオフライン結合。live APIの稼働や学術的妥当性の保証は含めない。
- 新Providerの追加やlive課金サービスのE2Eは追加候補。
