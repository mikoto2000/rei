# ChatGPT Plan Phase 2 implementation report

## Scope and existing behavior

Baseline main: 6aeb5e242610b64d672101e925ba4e3c1dfffe13 (PR #98).
Phase 0/1 already implement OAuth/DPAPI, account-specific catalog, read-only manual dispatch, scoped source snapshots, exact-input external-send approval, schema/source/completion checks, shared model budgets and Run lifecycle. Those implementations are reused without adding a second HTTP client or changing the normal Spring AI path.

Phase 2 adds optional destination fields to the existing delegateTask tool, an administrator-owned opt-in account/model allowlist, and explicit automatic dispatch through the existing backend. Legacy Java overloads and JSON agent/task/context calls retain their result format. The parent receives a receipt with state, ownership, usage, classified error, validated wrapper and structured validation errors. Its tool instructions explicitly require independent adoption/partial adoption/rejection and attribution.

## Components and changed files

- PlanDelegationProperties: new disabled-by-default automatic account/model policy.
- PlanConfiguration: configuration binding for the new policy.
- SubAgentTools: compatible optional fields and bounded requirements; only explicit Project-relative sources may be exported.
- SubAgentRunner: automatic dispatch; existing standalone/manual/local entry points remain.
- ChatGptSubAgentBackend: automatic policy gate before existing Permission, sources, shared budget, inference and verification.
- docs/chatgpt-plan-subagents.md: setup, JSON example, limits and approval/error recovery.

## Validation and security

TDD RED: SubAgentToolsTest ran 7 tests, with the new schema compatibility test failing because external fields were missing. GREEN: SubAgentToolsTest and ChatGptSubAgentBackendTest ran 28 tests, all passing, including four added tests (schema, actual Run routing, opt-in/allowlists, shared budget/approval/result checks).

Existing backend/client fixtures cover cross-Project source rejection, role/schema/source digest changes, failed validation, authentication/model availability, cancellation, quotas and unknown results. No authentication ceremony or real inference was performed. No API fallback, hidden context export, tool grants or new credential storage were added. Arbitrary prose completion criteria are task requirements, not an independent proof; expectedOutput retains the Phase 1 exact-field contract.

Local Maven configuration points to an unavailable gx10-6acc.local mirror. Test execution uses an ignored temporary settings file and existing workspace Maven cache with Maven Central for missing dependencies. Mockito self-attach is denied under restricted execution, so tests use the authorized normal execution context. User build.bat/build.sh changes and unrelated worktrees are preserved.

## Official specification

Checked 2026-10-11: overview, sign-in, models/inference, preview limitations, errors/recovery, app-server at https://developers.openai.com/siwc/token-sharing-open-source/ . The current HTTP requirements match the existing explicit input/store=false/stream=true adapter and unsupported-field exclusions. App-server integration remains Phase 4.

## Git lifecycle

Branch: feature/chatgpt-plan-phase2-auto-delegation.
Worktree: F:/project/rei/.worktrees/chatgpt-plan-phase2.
Commit/PR/merge and final full-regression totals are recorded in the final delivery report after CI.
