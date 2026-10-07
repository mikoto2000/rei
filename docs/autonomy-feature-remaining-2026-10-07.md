# 自律機能候補の残件再評価（2026-10-07）

## 新しい全件再監査

後続の全件実装依頼については、最新main `905cd4ee` を基準にした
[全件再監査・実装記録](implementation-report-remaining-features-2026-10-07.md)を参照する。
このページのbounded実装済みという表記を、追加22優先項目の完成へ読み替えない。
全件依頼のPriority 1–6では、会話並行化、Task Manager、共通Artifact Delivery、
GitHub Event Trigger、Cross-project Today、Codex隔離実装を実装・統合した。
Claudeのresume、Change Set、並列review、隔離実装は追加回帰中。
Priority 7以降は全件実装記録の進捗を参照する。実モデル・実サービス検証とは区別する。

元の依頼は「現在のリポジトリ・環境で安全に実装できる項目」を小さな独立機能として実装し、候補全部の無条件実装や既存機能の二重実装を避けるものだった。後続の全対応要求を受け、具体的な不足を再照合して追加実装した。過去のfollow-upの「未対応」はその記録時点の状態であり、現在の判定は[監査表](autonomy-feature-audit.md)を参照する。Deferredを実装済みへ読み替えない。

| Priority | 現在の対応 | 状態 / 残る範囲 |
|---|---|---|
| A1–A7 | 自動記憶整理、Planning/Goal/予算、Scheduler/Dependency、Policy/承認、Inbox/webhook、Reflection/検証済み記憶 | bounded実装済み。hard kill hook保証・任意code条件・provider固有通知・自由文からの一般化は追加候補 |
| B8–B10 | 週月分析、分類基準/Coaching、自動通知・HTTP/Native設定、観測時Work Context保存/HTTP/Native閲覧 | bounded実装済み。観測からの意味的task帰属や個人化品質は未評価 |
| B11–B13 | 文書RAG、FTS5/BM25/dense/RRF/rerank、Semantic Skill Search/永続索引 | 既存再利用＋opt-in実装済み。学習sparse・実データ品質評価は未対応 |
| C14–C16 | evidence contract、独立意味検証、bounded修復、モデル/READ Tool一時障害retry、独立並列SubAgent | 実装済み。実モデル判定品質・別モデル合意・durable子resume/DAGは追加候補 |
| C17 | 保存review/明示native継続/修正案→Change Set→Apply→別Run再レビュー、独立並列Codex | bounded実装済み。Claude Codeのsubscription認証reviewを後続依頼で追加。他providerは追加候補。CLI直接書込は未実装 |
| C18–C20 | Java/Git Map/永続索引/summary、構造的test/module/regression候補、ログ/JUnit単一/集合診断 | bounded実装済み。多言語完全意味解析・coverage完全性・その他report形式は未対応 |
| C21 | 明示test→静的diff確認→保存修正案Apply→全サイクル再検証 | bounded実装済み。意味的自己reviewや全Goal強制gateは追加候補 |
| D | 既存UTF-8文書/Mermaid/PlantUML自然言語編集案、保存差分/明示Apply、Paper Provider条件検査/保存/引用/再起動fixture | bounded実装済み。複数ファイル/binary編集・renderer・live provider E2Eは未対応 |

## 未実装候補と次の条件

| Priority | 候補 | 現在の制約 / 次に必要なもの |
|---|---|---|
| B | Activityの意味的個人化/帰属、RAG/Skillの学習・実データ評価 | 正解付き・利用許可のある評価データ、品質指標、採用モデル/学習方式。保存観測やmock成功だけで品質を保証しない |
| C14 | 意味検証の実モデル品質/別モデル合意 | 評価用task/evidence/counterexampleと使用provider/予算。固定fixtureの制御フロー検証と区別する |
| C15–C16 | durable SubAgent resume、DAG/合意形成 | 元候補のbounded repair/独立batchを超える追加仕様。未知Tool副作用のreconcileと再開ownershipを先に定義する |
| C17 | 別vendor External Agent | 現環境でPATHに見つかったのはCodexのみ（Get-Commandの読取り確認）。2026-10-07に一度保留した後、Claude Code追加を依頼。Claude Codeのsubscription認証・bounded snapshot・Tool無効化・共通予算/履歴routerを追加した（claude-code-reviews.md）。CLI installation/loginは利用環境で必要。その他providerは追加候補 |
| C18–C20 | 多言語の完全依存、coverage、他report形式 | 対象language/build/reportを選び、既存候補/partial表示を保持した独立parserから追加する。完全性を現実装の保証にしない |
| C21 | 意味的自己review/全Goal強制統合 | 判定contract・対象patch/requirement/evidence・共有予算・失敗時の完了条件を追加仕様として定義する |
| D | 複数ファイル/binary/renderer/live Paper E2E | 原子的適用/復旧方式、対象format/renderer/providerと評価環境。現在の単一テキスト適用やoffline Provider fixtureはこれらの完了ではない |

元候補を超える拡張が存在することと、対応した基本機能の不具合・具体的な未統合を分ける。別provider追加の保留は過去の回答時点の状態。その後のClaude Code追加依頼を反映した。実装・Git・検証結果は[継続記録](autonomy-feature-progress-2026-10-06.md)と機能別reportに保存する。
