# embedding親モデル予算 実装記録

A2 の embedding 費用予算へopt-inで対応した。BeanPostProcessorは有効時だけSpring EmbeddingModelへadapterを接続し、ToolContext／Skill選択／専用workerで親ModelCallBudgetを明示伝播する。実call直前予約・報告usageのvector利用前計上、document formatとbatch APIの保持、次元probe計上とcache、owned/未所有の分離を実装した。Semantic keyword fallbackとSQLite rollback後の例外変換でtyped予算停止を吸収しない。

初期Redは未実装adapter／scope APIによるcompile失敗。Green後、実SQLite chunk batchの予算停止が汎用更新例外に包まれる1 failure / 0 errorsを検出し、rollback後のtyped伝播を修正した。新規9テストで、正確な上限・未知／0／超過／provider障害、document format・次元probe、worker伝播と次タスクの非継承、ToolContext伝播・scope復元、実Goal SQLite再起動、opt-in/default Bean、実Skill選択からのfallback／implicitモデル抑止、実vec0 chunk batch rollbackと費用非返金を検証した。

全体回帰3192 tests / 601 suites、failure/error/skip各0。feature `934880b4` をPush、main `efedc73a` へMerge。Merge後のEmbeddingRunModelBudgetTest / SkillSelectionRunBudgetTest / AgentSkillSelectionServiceTest / SkillEmbeddingClientTest / ToolEventCallbackDecoratorTest / SemanticSkillSearchTest / SqliteVectorStoreTest / ExternalConfigFileServiceTestはPASS、main Push済み。Java/configのみでNative/Reactは再実行していない。

[適用範囲・probe・保存境界](embedding-run-model-budget.md)。rerank費用予算などは残件。
