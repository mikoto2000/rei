# Sleep共有モデル予算 実装レポート

## 実装

MemoryProperties.Sleepへ既定0のmax-llm-calls/max-total-tokensを追加し、1回のSleep内で候補抽出と意味解決に同じSleepModelBudgetを明示的に渡す。既存OutputLimitRunBudgetとModelCallBudgetを再利用し、ThreadLocalや新しい予算DBは使わない。

LlmMemoryProcessorは実呼出し直前に回数を予約し、集約usageを解析前に計上する。予算・不明使用量停止はfallbackに隠さない。決定的な解決はモデルを呼ばず、全計画の確定前に失敗した場合は既存の記憶／checkpointの原子的保存へ進まない。旧API、FunctionalInterface、Sleepの旧constructor、既定無効時の旧port経路を維持する。token上限に未対応のcustom portは拒否する。

## 検証

初期RedはSleep予算設定constructorの未実装で確認した。実装後の最初のコンパイルで、テストのlambdaが再代入変数を参照していたため修正した。

Greenでは抽出の超過による記憶非保存／checkpoint非進行、抽出と解決の共通呼出回数、解決の超過による部分計画非保存、上限ちょうどで決定的NEWの保存、usage欠落・Provider障害、既定無効の旧動作、previewの監査非保存、完全一致重複の追加モデル非消費、次のSleepでの独立予算、未対応port拒否、負・過大設定の拒否とSpring bindingの既定値を確認した。複数chunkの最終usage計上も検証した。

Sleep／Auto Sleep／cron／Memory境界／出力schema／記憶保存／設定テンプレートの関連テストはPASS。全体回帰は3089 tests / 590 suites、failure/error/skip各0でPASS。Javaのみ変更し、Native/Reactは再実行していない。Git結果はMerge後確認を完了して追記する。

## 制限

1回のSleep処理の予算であり、複数処理を跨ぐ永続token上限・Auto Sleepの全期間の費用上限ではない。既存のAuto Sleep retry動作を変更しない。API報告後の制限で、実Provider・実LLMの品質評価は行っていない。適用範囲は[sleep-model-budget.md](sleep-model-budget.md)。
