# 親RunなしSubAgentモデル予算 実装記録

C16 の未所有経路の費用予算へ対応した。直接呼出は1実行、並列実行は1バッチの共有呼出回数／報告token予算をopt-inで設定する。既存の子cycle・構造修復・意味検証へ同じ予約を接続し、明示親Run/Goal予約を優先する。既定0では従来経路を維持する。

未実装設定／setterのcompile Red→実runner最小Green→関連検証を行った。新規9テストで実streaming runner、並列のatomic回数制限と入力順、既に送信済み費用超過後の後続抑止、修復・judgeの共有、未知／0／provider障害、親予約優先、独立バッチ・既定互換、設定binding、timeout後のunknownを検証した。既存親Run予算の関連テストもPASS。

全体回帰3209 tests / 602 suites、failure/error/skip各0。feature `6aa91130` をPush、main `267b70d1` へMerge。Merge後のStandaloneSubAgentBudgetTest / SubAgentRunnerTest / SubAgentSemanticValidationTest / ParallelSubAgentDelegatorTest / SubAgentSharedRunBudgetTest / ExternalConfigFileServiceTestはPASS、main Push済み。Java/configのみでNative/Reactは再実行していない。

[共有範囲・並列in-flight・既定設定](standalone-subagent-budget.md)。DAG／合意形成／永続復旧などは残件。
