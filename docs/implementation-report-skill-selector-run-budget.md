# Skill選択用LLM予算の実装レポート

## 問題と変更

暗黙のSkill選択がRun/GoalのLLM呼出回数に含まれていなかった。既存RunExecutionContextのconsumeNextLlmCallをAdvisorから選択Serviceへ渡し、標準Selectorのモデル呼出直前に実行する。新しい予算基盤は追加しない。

予算不足をSelectorの通常エラーfallbackの外で伝播する。空候補・明示指定による選択完了には追加消費がなく、API失敗は消費済みとして扱う。旧APIとFunctionalInterfaceの互換性を維持する。

## 検証

新規SkillSelectionRunBudgetTestで共通親予算の枯渇、実モデル呼出回数、空候補、失敗時消費、Advisor経由の引継ぎ、明示指定、取消を検証した。追加API未実装のRedを確認後、関連テストはGreen。全体回帰は3030 tests / 579 suites、failure/error/skipはいずれも0で通過。最初の全体回帰で旧APIをmockしたキャンセル試験1件が失敗したため、予算付きAPIと実予約callbackへ更新し、元の停止条件を保持して全体を再実行した。Merge後にも関連テストを確認する。

## Git

ブランチはcodex/skill-selector-run-budget。feature Commit、Push、main Merge、Merge後テスト、main Pushの順に実施する。ハッシュはGit履歴で確認できる。

## 制限

RunContextのない直接呼出、embedding/rerank、共通token上限は今回の対象外。主回答用に既に予約した回数は払い戻さない。モデル品質はHTTP/LLMの実サービスへ接続せず、境界テストで検証した。
