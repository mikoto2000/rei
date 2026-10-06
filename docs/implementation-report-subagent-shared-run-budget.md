# 通常ChatのSubAgent共通Run予算 実装レポート

## 問題と実装

既存SubAgentへの共有予約はGoalの永続予約だけを渡し、通常Chatではnullになった。そのため子が親の局所Run呼出上限に計上されず、Goalがある場合も局所上限を子が回避できた。

RunExecutionContext.sharedLlmReservationを同期された親カウンタの予約adapterに変更した。既存OutputLimitRunBudgetが局所上限→Goal予約の順に計上するため、子・並列子にも両方の制限が適用される。新しい予算基盤やモデルTool引数は追加しない。

## 検証

通常Chatの親子回数・並列残1回・Goalによる局所上限回避の3テストでRedを確認後、最小実装でGreenにした。意味検証の追加呼出しも局所予算へ計上し、取消済み親からの子呼出しがモデル開始前に止まることを追加確認した。既存SQLite Goal／Chat／子cycle／修復／並列／Skill予算の関連テストも通過。全体回帰3054 tests / 584 suitesはPASS（failure/error/skip各0）。Git実ハッシュは履歴と継続記録へ保存する。

## Gitと範囲

ブランチcodex/subagent-shared-run-budget、base main。feature Commit/Push→main Merge→Merge後確認→main Pushの順に実施する。RunContextなしの旧手動APIと子自身の制限は維持する。共通token上限・embedding/rerank上限は対象外。
