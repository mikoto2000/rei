# Goal の事実に基づく Reflection

Status: Implemented (Goal evidence slice)

Branch: `codex/goal-reflection`

Commit / Merge: `feat: persist fact-based goal reflections` / `Merge branch codex/goal-reflection` を参照。

Merged into: `main`

Implemented:
- SQLiteの期待ファイル条件、保存された検証区分、差分、固定レビュー提案。
- Project/Session/Goal/source Run/source event参照。Goal状態ごとの一回限り保存・再起動後も重複抑制。
- 検証成立、不一致、検証拒否、未観測を区別。承認待ちや実行失敗を検証済みの教訓と扱わない。
- 遅延イベントをsource Runと試行番号へ関連付け、最新の成功結果と混同しない。
- /reflection list|show|collect。明示collectは停止済みGoalの保存状態のbackfillで、再実行・再検証をしない。
- 追加LLM、Memory書き込み、自動承認・再試行・予算補充なし。既存Sleep/Work Contextを重複実装しない。

Tests:
- Red: 未実装Repository/Serviceによるcompile failureを確認。
- Green: 期待差分、承認待ちの未検証、条件成立、重複抑制、再起動、Project境界。
- 遅延イベントがsource試行を利用すること、目的文をReflectionへコピーしないこと。
- Shellの所有境界、collectがGoalの試行数を変更しないこと。
- Goal loop/gatewayの関連回帰PASS。
- 全 Java: 2,738 tests / 528 suites、failure=0 / error=0 / skipped=0。
- JDK25、既存cache、`mvnw.cmd -o -Dmaven.repo.local=F:\project\rei\.m2\repository -Pfull test -q`。
- diff review / `git diff --check` PASS。

Result: PASS

Remaining:
- 一般Task/ChatのReflection、意味的原因分析、ユーザー評価、validated memory昇格。
- 次の計画への自動フィードバック、専用Web/Native UI、retention。
- 過去の未配送イベント全件の自動backfill。collectは現在の保存状態だけを使用する。
- 外部サービスや追加モデルは不要。
