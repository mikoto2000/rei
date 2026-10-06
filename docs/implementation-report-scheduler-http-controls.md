# Scheduler HTTP controls

Status: Implemented

Branch: `codex/scheduler-http-controls`

Merged into: `main` after validation; commit identities are recorded in Git history.

The authenticated Project-scoped API exposes existing persistent schedules without adding another scheduler. GET list, detail and history read saved state. Detail includes interval milliseconds and remaining occurrences, cron expression/time zone and remaining occurrences, or the exact event trigger. Lists retain the repository's 256-entry bound. Creation continues through existing scheduler Tools and CLI controls.

Endpoints under `/api/v1/projects/{projectId}/schedules`: GET root, GET `/{id}`, GET `/{id}/history`; POST `/{id}/activate`, `/{id}/cancel`, POST `/{id}/reconcile`. Activation uses the existing PENDING state transition and expiry rules; dispatch still requires both `rei.agent-scheduler.enabled` and `rei.tool-permission.enabled`. Cancellation accepts only unclaimed schedules, leaving active Run cancellation to normal Run controls. Interval and cron repeats retain their bounded occurrence and missed-time handling.

Reconciliation requires `{"expectedRunId":"the-observed-run","acknowledgeUncertainSideEffects":true}`. The existing dispatcher rejects queued/executing Runs before the repository atomically checks the exact Project, schedule, RUNNING state and saved Run ID. An uncertain claim becomes FAILED with `uncertain_run_reconciled`; it is never declared successful, replayed, or given restored repeat budgets. State conflicts return the existing safe HTTP 409 response, and foreign or malformed requests return 400. No automatic POST retry is introduced.

TDD first failed on the missing controller. Two controller tests verify read-only behavior, explicit acknowledgement, exact service delegation and single invocation on conflicts. A real authenticated HTTP/SQLite test covers discovery, interval metadata, Project ownership, explicit activation and cancellation, in-flight rejection via the dispatcher boundary, saved uncertain claim reconciliation, repeated mutation conflicts and absence of replay. Existing dispatcher and restart recovery tests independently cover their real in-flight/state guards. The HTTP fixture mocks only the dispatcher; it does not claim to exercise actual schedule dispatch or Run/SSE integration.

Tests: full offline Maven `-Pfull test` passed 2969 tests in 569 suites, with zero failures, errors or skipped tests, including the separate remote Feed authentication changes merged before this branch. Result: PASS. Post-merge focused HTTP/controller, dispatcher and recovery tests are run before pushing main.

Remaining: Scheduler RunRegistry/RunService tracking and Native recovery controls; other incomplete original audit requirements remain outstanding.
