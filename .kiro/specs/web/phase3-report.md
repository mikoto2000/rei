# Phase 3 実装報告

Branch: feature/web-api-phase3

Base branch: feature/web-api-phase2 (ac92b1b)

Commit: この報告を含む `feat(web): add async run APIs for summarize and image`

Implemented endpoints: POST /api/v1/summaries、POST /api/v1/images（202、Location、runId）。状態・events・cancel は既存 /api/v1/runs/{runId} を利用。

New/changed components: BackgroundRunController、BackgroundRunConfiguration、BackgroundRunSubmitService、ConversationInputRouter.submitOperation、RunResponse、AgentEvent.withOwnership、CurrentConversationHistoryAppender。

Important design decisions: 既存 Run lifecycle/DTO/SSE/replay を再利用し job API/status enum を追加しない。chat と同じ project FIFO を共有。非会話操作の sessionId/turnId は null、SessionRepository/TurnStore を汚染しない。画像パスはサーバー生成。既存 core services を Shell/Web で共有。

Tests added: BackgroundRunApiTest（queued/running cancel、polling、FIFO/並行実行、SSE replay、validation）、BackgroundRunHttpTest（認証・実 HTTP 202/Location/JSON/SSE）、OperationHistoryIsolationTest（共有履歴副作用）。未実装型の Red と、実 appender が共有履歴へ書く Red を確認して実装。

Test result: Java 全テストの最終結果は tasks.md に記録。Client npm test: 42 passed。Native Client cargo test の最終結果は tasks.md に記録。

Known limitations: Run/replay は既存の in-memory/30分保持。画像は project のサーバー管理ディレクトリに保存し、HTTP ダウンロードは未提供。外部 LLM/生成 API はモックで検証。Shell background の独立した execution 表示は維持。

重大な調査結果: 既存 DefaultWebPageSummarizerService は CurrentConversationHistoryAppender を通じて現在の会話へ追記する。非会話 Web run だけ追記を抑制し、CLI の追記を保った。Phase 1 設計の ProjectRunQueue は AGENT-only と記載されていたため、Phase 3 要件に従い明示 Web operation の受付も許す。既存 Shell background は従来の経路のまま。

git status: 開始時からの6ファイルの変更を保持し、Phase 3 コミットから除外する。
