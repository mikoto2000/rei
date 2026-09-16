# Implementation journal

Each slice starts with a failing behavior test, then implementation, then formatting/refactoring.

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
