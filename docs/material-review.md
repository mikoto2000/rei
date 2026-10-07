# Codex 教材レビュー

```text
/agent codex material-review [target]
/agent codex material-review .
/agent codex material-review docs
/agent codex material-review ./training/spring-security
```

技術系の勉強会・研修・ハンズオン資料を、技術的正確性・教育設計・説明品質・横断的一貫性・実務適合性の観点からCodexにレビューさせます。通常の `/agent codex review` は一般的なコード・設計レビューのままです。教材レビューは専用の `MATERIAL_REVIEW` タスクであり、自動修正コマンドではありません。Claude向けの `material-review` は提供しません。

targetは通常レビューと同じ `ExternalAgentRequest.resolveTarget` を使います。現在Projectを基準に相対パスを解決し、絶対パスもProject内に限って許可します。symlinkを解決した実体がProject外にある場合や、存在しないtargetはCodex起動前に拒否します。省略時も既存と同じnull targetを渡し、教材仕様は現在Projectの教材構造を探索させます。Working Setや設計決定は調査の手掛かりにします。

## レビューの内容

1. README、依存、サイト設定、sidebar/navigation、画像、サンプル、検証コマンドから全体構造・読者・前提・学習順序を確認します。推定を事実と区別します。
2. 公開対象のMarkdown / MDXを確定します。依存・生成物・vendor・第三者資料・自動生成API資料は通常除外し、除外対象を記録します。
3. 対象ページをすべて読み、技術的正確性を最優先にレビューします。
4. 全ページ読了後に用語・重複・矛盾・前提・順序・設定値・version・実務上のメッセージを横断評価します。
5. 可能な範囲でbuild、lint、Markdown lint、link check、example test、repositoryの検証を行い、未実行の理由を記録します。

必須の9軸はTechnical Accuracy、Instructional Design、Presentation / Teaching Usability、Reader Comprehension、Code Samples、Markdown / Documentation Site Quality、Cross-page Consistency、Practical Applicability、Content Volume and Time Allocationです。社内事情や開催時間を勝手に補完しません。

FindingはCritical / High / Medium / Lowに分け、file、heading、行番号（不明ならnull）、カテゴリ、問題、影響を受ける読者・理由、改善方向を示します。5軸を0〜5点で理由とともに評価し、良い点と維持すべき構成、図・コード例・デモの追加候補、Appendix候補、修正優先順位も報告します。

## 出力・実行上の制約

Codexへ渡す仕様は [`material-review.md`](../src/main/resources/external-agent/material-review.md)、出力スキーマは [`material-review.schema.json`](../src/main/resources/subagents/schemas/material-review.schema.json) としてrepository内で管理します。ユーザー環境の外部SKILL.mdを必要としません。既存SubAgentのstrict JSON parserとJSON Schema validatorを再利用します。

検証済みJSONからアプリケーションが15節の「勉強会資料レビュー」を生成し、`materialReviewReport`として通常の回答生成に渡します。summaryやFindingの既存保存上限とは別に、最大120,000文字のレポートを機密情報のマスク後に保存します。既存の保存レビュー取得Toolからも参照できます。読み切れなかった対象・除外・推定・検証制約を記録し、未検証を成功とは扱いません。

既存のread-only・network無効のCodex permission profile、CLI capability確認、Runあたり1回の委譲、timeout、cancel、予算、イベント、Project別履歴を共有します。資料本文、navigation、依存、version、コードは自動修正しません。生成ファイルを書き込むbuildやnetworkを必要とするlink checkは実行できない場合があり、`NOT_RUN`と理由を報告します。権限を緩めて再実行しません。

不正なJSON、必須項目不足、不正severity、スコア範囲外、出力切断、レポート上限超過は`FAILED`です。通常レビューの既存fallbackは維持します。External Agentに既存の自動repair/retryはないため、このコマンドでも追加しません。失敗理由を示し、保存された実行結果を確認してから新しいRunで依頼します。

有効な教材レビューを再レビュー・native session継続する場合も教材タスクを保持します。session継続は既存の管理者opt-in・未消費session要件に従います。
