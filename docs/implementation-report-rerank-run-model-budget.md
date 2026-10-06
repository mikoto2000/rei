# rerank親モデル予算 実装記録

A2 の rerank 費用予算に opt-in で対応した。既存 HTTP 送信直前に親 Run/Goal の共有回数を予約し、strict JSON の usage.total_tokens を結果利用前に計上する。未知・非正値・型不正・overflow・応答サイズ超過・曖昧 JSON・provider 障害の停止、既知費用の結果不正 fallback、Semantic Skill の typed 停止伝播、既定互換を追加した。

初期 Red は設定 constructor 未実装の compile 失敗。最小 Green 後、Semantic Skill の fallback が親予算停止を吸収する 1 failure / 0 errors を検出し、修正した。新規8テストで実ローカル HTTP、正確な上限・送信抑制・取消、各種不正 usage／JSON、実 Semantic 検索、実 Goal SQLite の再起動保持、設定 binding を検証した。

全体回帰3200 tests / 601 suites、failure/error/skip各0。feature `0175653f` をPush、main `7d0c674f` へMerge。Merge後のRerankServiceTest / SemanticSkillSearchTest / VectorDocumentServiceTest / ExternalConfigFileServiceTest / EmbeddingRunModelBudgetTestはPASS、main Push済み。Java/configのみでNative/Reactは再実行していない。

[適用範囲・provider報告契約](rerank-run-model-budget.md)。汎用Goal検証条件などは残件。
