# Implementation journal

Each slice starts with a failing behavior test, then implementation, then formatting/refactoring.

## Live AgentEvent activity (2026-09-17)

Follow-up `feature/native-interleaved-events`: Red (missing timeline DTO) → Green
(immutable event snapshots interleaved with adjacent text segments); Red (unknown
run type adds a row) → Green (explicit lifecycle allowlist); Red (Chat DOM order
missing) → Green (RunTimeline presentation); Red (replay gap hides stored final
answer) → Green (explicit recovered final answer). Reconnect integration verifies
the start snapshot remains RUNNING after the later completion snapshot arrives.

The current work and actual Red/Green observations are recorded in
[the live event TDD ledger](../docs/native-live-agent-events.md#tdd-ledger).
Slices cover public mapper/security, message/tool state, LLM/progress/Working Set,
skill correlation/ownership, HTTP reconnect, presentation DTOs and collapsible UI.
No live activity is added to Session Turn history or persistent settings.

## Phase 1A / 1B

- `foundation`: Red (missing domain) → Green: 4 tests.
- `storage`: Red (missing repository/credential ports) → Green: 3 tests, including encrypted reopen and wrong password.
- `http`: Red (missing ReiClient adapter) → Green: 4 tests using an actual local Axum server.
- Full suite: 11 passing. Stronghold candidate could not compile because libsodium's external download host was unavailable. Chosen portable encrypted vault uses Argon2id and authenticated XChaCha20-Poly1305; unlock key is never saved.

No production API key or live server is used by tests.

## Phase 1C / 1D

- `projection`: Red (missing Projection / parser) → Green: delta, UTF-8 framing, terminal, duplicate, unknown/malformed event, actual tool/working-set schema.
- `streaming`: Red (missing RunManager / observer / notification ports) → Green: real HTTP disconnect/reconnect with Last-Event-ID, duplicate replay, replay gap recovery, explicit cancellation, multiple servers.
- Full Rust core suite at this checkpoint: 22 passing.

## Phase 2A

- `conversations`: Red (missing service) → Green: create, persistence/reopen, project locking, session binding and explicit continue-as-new.
- `application`: Red (missing composition service) → Green: server CRUD and redacted credential DTO, submit → automatic SSE subscription → resume → expiry without implicit fork.
- Full Rust suite: 28 passing.

## Phase 2B / 2C and native integration

- `models.test.ts`: Red (missing view model) → Green: active filtering, concurrent identity, stale snapshots, submission guard and safe errors.
- `Chat.test.tsx`: Red (missing component) → Green: project lock, incomplete history, explicit Run Stop and explicit expiry recovery.
- `App.test.tsx`: Red (missing app) → Green: Vault setup, project ID based creation/navigation, safe native-boundary failure.
- Settings credential test verifies immediate input clearing. Playwright verifies actual Desktop/Mobile layouts and run → conversation navigation, cancel, creation and connection dimensions.
- Refactor: application receives Repository / ClientFactory / CredentialFactory ports; composition root owns concrete adapters. UI boundary owns DTOs; native plugin remains outside application.

## Boundary hardening

- Missing project without session incorrectly returned SessionNotFound: failing HTTP test → ProjectNotFound mapping.
- Initial title did not reflect the submitted prompt: failing application test → first-turn metadata binding.
- Mobile cleartext policy missing: failing test → HTTPS-only mobile native adapter.
- Random HashMap run order and manual terminal recovery without incomplete flag: two failing streaming tests → ordered registration and explicit incomplete recovery.
- Additional regressions cover cross-run/mismatched sequence rejection, heartbeat with ID, oversize/invalid UTF-8, failed notification with denied permission, gap polling while still RUNNING, and terminal status during an outage.

The platform check matrix and environment blockers are recorded in VALIDATION.md.

## Session History（2026-09-17）

1. Rust の Session model / HTTP / service / DTO テストを先に追加し、未実装 symbol の compile failure を確認。domain・HTTP DTO・application service・command を実装し50件成功。既存の永続化テストは新しい非永続化契約に変更。
2. HistoryPager の5テストと SessionSelection の3テストを先に追加し、module 不在の Red を確認。paging / generation / explicit retry / selection isolation を実装して Green。
3. App.history の5テストを追加し、既存画面で5件失敗を確認。remote list / project filter / more / detail / locked resume / 404 recovery を接続し Green。
4. 保存済み Turn と runtime Run の重なりに対して timeline テストを追加し、module 不在の Red を確認。サーバー順を保つ merge を実装して Green。
5. terminal refresh の回帰テストを追加して、delta では取得せず terminal ごとに1度だけ一覧・履歴・metadata を更新することを確認。
6. 最終レビューでローカル touch がサーバー更新日時を上書きする問題を修正し、回帰テストを追加。これは修正後に追加したテストであり Red-first としては扱わない。

最終結果: Rust51件、frontend27件、browser6件成功。型・lint・format・native check/clippy・desktop build 成功。Android は NDK compiler 不在、iOS は Windows 環境のため未検証。詳細は VALIDATION.md。
