# 外部Codex CLI親モデル予算 実装記録

A2 の CLI 費用予算へ、opt-in `inherit-run-model-budget` で対応した。review／resume／修正案の有料プロセス呼出前に親Run/Goal回数を1回予約し、完全な単一turn JSONLの報告input＋output tokenを構造化出力の解析／保存前に計上する。能力確認helpを除外し、unknown／超過／枯渇のtyped停止を外部委譲層で吸収しない。停止したreviewも履歴／eventをFAILED終端にし、取消はCANCELLED、未報告予約を返金しない。既定false・旧executor経路・既存明示認可とread-only境界を維持した。

初期Redは未実装の設定／接続APIによるcompile失敗。CLI process runner fixture＋実Codex executor＋実委譲serviceで、9テストを追加した。正確な上限／次呼出停止・枯渇後paid非起動・超過計上、非正値／非整数／overflow／duplicate key／複数turn／不完全／切詰め／timeoutの未知停止、取消signal、実SQLite Goal再起動後token／回数保持とreview FAILED audit、既定互換・help能力不足の非課金、超過fix proposal後のChangeSetサービス非接触、設定binding・非対応executorの迂回禁止を検証した。live Codex/providerは呼んでいない。

全体回帰3183 tests / 600 suites、failure/error/skip各0。feature `5b397165` をPush、main `eb46a810` へMerge。Merge後のCodexRunModelBudgetTest / CodexExternalAgentExecutorTest / ExternalAgentDelegationServiceTest / ExternalFixProposalTest / ExternalReviewHistoryTest / ExternalConfigFileServiceTestはPASS、main Push済み。Java/configのみでNative/Reactは再実行していない。

[報告usage・CLI内部cycleの限界](codex-run-model-budget.md)。embedding／rerank費用予算などは残件。
