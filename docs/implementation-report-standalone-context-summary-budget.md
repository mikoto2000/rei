# RunContextなし会話要約予算 実装記録

A2 の RunContext なし要約予算へ対応した。ContextCompressionProperties に opt-in の呼出回数／報告total token上限を追加し、1回のContextAssembler組立ての履歴・実行ログ要約で共有する。直接compressor呼出は各操作独立。モデル呼出直前の予約・usage計上後に出力検査を行い、未知・超過・provider障害・timeoutをtyped停止signalで伝播する。既存Run/Goal予算を優先し、既定無効時の4引数functional interface経路を維持した。

初期Redは未実装設定setterによるcompile失敗。修正後、RunContextなしの超過／未知usage停止のGreenを確認。追加7テストで実ContextAssembler・summaryファイル保存・mock ChatModelを使い、履歴＋実行ログの共有回数／token予算、先に保存済みのsummary維持と停止したsummary非保存、未知usageの縮退抑止、直接呼出の独立性・上限ちょうど、provider障害／timeout取消、既存Run予算優先、設定binding／validation・非対応compressorの迂回禁止を検証した。

全体回帰は3172 tests / 599 suites、failure/error/skip各0。feature `f25a564e` をPush、main `87d892d5` へMerge。Merge後の ContextSummaryTokenBudgetTest / ContextCancellationTest / ContextAssemblerTest / ExternalConfigFileServiceTest はPASS、main Push済み。Java/configのみでNative/Reactは再実行していない。

[適用範囲・保存・制限](standalone-context-summary-budget.md)。CLI／embedding／rerank費用予算などは残件。
