# Phase 4 実装報告

Branch: feature/web-api-phase4

Base branch: feature/web-api-phase3 (af12a60)

Commit: この報告を含む `feat(web): add stateful write APIs`

Implemented endpoints:

- POST /api/v1/feed、PATCH/DELETE /api/v1/feed/{id}
- GET/POST /api/v1/reminders、GET/DELETE /api/v1/reminders/{id}
- GET/POST /api/v1/interests
- GET/POST /api/v1/memories、GET/DELETE /api/v1/memories/{id}
- POST /api/v1/skills/reload
- profile は Phase 2 の read のみ（既存 write operation なし）

New/changed components: StatefulController、StatefulOperationService、SkillReloadService、OperationConflictException、StrictApiRequest、専用 request/response DTO、ApiExceptionHandler。ReminderService の既存 findById の可視性を public に変更。Shell skill reload は共通サービスを利用。

Important design decisions: domain の既存 add/update/save/delete/status operation のみを明示公開。全体共有データに偽の project/session 所属を付けない。DTO の未知フィールドは400にし、pathや偽の所属を拒否する。delete は feed/reminder が未知も204、memory は既削除204/未知404。profile 更新、skill ファイル作成/変更/削除、memory export 等の endpoint は作らない。

Tests added: StatefulApiTest（SQLite の永続化・create/read/update/delete・400/404/409・enum・未知フィールド・冪等性・contextual memory 非公開）、StatefulHttpTest（実 HTTP/Bearer/JSON/非公開操作/再起動後の永続性）、V1DtoContractTest（Phase 2〜4 の全 response DTO の JSON field 名を固定）。未実装サービスのコンパイル失敗を先に確認して実装。

Test result: 最終 Java/Client/Native Client の結果は tasks.md に記録。外部サービスの投稿/OAuth/任意パス操作は endpoint を作らず、認証済み HTTP でも404/405を検証。

Known limitations / 重大な調査結果:

- 既存 MemoryScope.PROJECT/SESSION は所有 ID を保持する列がない。その scope の memory を安全に HTTP 公開するためには、先に所有情報の永続モデルが必要。今回の adapter は当該 scope の作成を400、参照/削除を404、一覧から除外する。既存 CLI は変更しない。
- interest は既存 save/list を使う登録 API で、discovery の外部検索を自動公開しない。既存ドメインに edit/delete がないため作らない。
- memory content の edit、reminder の edit、profile write、skill ファイル CRUD は既存にない。update API を揃えるために新ドメイン操作を追加しない。
- UI 新画面や画像ダウンロード、永続 Run キューは今回の対象外。

git status: 開始時からの docs/configuration.md、docs/usage.md、BriefingService.java、ReminderJob.java、BriefingServiceTest.java、ReminderJobTest.java の変更は保持しコミットから除外。全テストはこれらを含む作業ツリーで検証。

Stack: main → feature/web-api-phase2 → feature/web-api-phase3 → feature/web-api-phase4。
