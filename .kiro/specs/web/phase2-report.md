# Phase 2 実装報告

Branch: feature/web-api-phase2

Base branch: main (015b868)

Commit: この報告を含む `feat(web): add read-oriented APIs`

Implemented endpoints:

- GET /api/v1/feed、/feed/{id}
- GET /api/v1/skills、/skills/{name}
- GET /api/v1/profile
- GET /api/v1/briefing
- POST /api/v1/search
- history: 既存 /api/v1/sessions と詳細・turns を再利用

New/changed components: ReadController、ReadQueryService、SkillQueryService、ProfileQueryService、専用 DTO、ApiExceptionHandler、Shell skill/profile の参照サービス共通化。

Important design decisions: コマンドを HTTP expose しない。全体共有ドメインを project 所属と偽らない。profile はイベント統計で更新操作なし。skill は登録名で参照しパスを公開しない。未知 Session は404、未知 project filter は既存 v1 の空一覧契約を維持する。search は既存検索サービスの結果を返す。

Tests added: ReadApiTest、ReadHttpTest。Red は未実装型のコンパイル失敗および安全な500変換の未実装を確認。Green 後に共通サービス抽出。全テストで既存400の回帰を検出し TypeMismatch の mapping を修正。

Test result: Java 全テストの最終結果は tasks.md に記録。Client npm test: 42 passed。Native Client cargo test: 全テスト成功。外部ネットワーク/LLM 実サービスの呼出はモックで検証。

Known limitations: グローバル検索/ブリーフィングは同期実行。profile/skill には既存に作成・更新・削除がない。UI の新画面は追加していない。

git status: 作業開始時から存在した docs/configuration.md、docs/usage.md、BriefingService.java、ReminderJob.java と対応する2テストの変更は保持し、コミット対象外。検証はそれらを含む作業ツリーで実行。
