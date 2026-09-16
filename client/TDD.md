# Implementation journal

Each slice starts with a failing behavior test, then implementation, then formatting/refactoring.

## Phase 1A / 1B

- `foundation`: Red (missing domain) → Green: 4 tests.
- `storage`: Red (missing repository/credential ports) → Green: 3 tests, including encrypted reopen and wrong password.
- `http`: Red (missing ReiClient adapter) → Green: 4 tests using an actual local Axum server.
- Full suite: 11 passing. Stronghold candidate could not compile because libsodium's external download host was unavailable. Chosen portable encrypted vault uses Argon2id and authenticated XChaCha20-Poly1305; unlock key is never saved.

No production API key or live server is used by tests.
