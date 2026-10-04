# Human dependency answer HTTP controls

Branch: `codex/dependency-human-controls`

The authenticated Project-scoped API now exposes dependency list, detail and saved observation history, plus explicit human-answer submission at `/api/v1/projects/{projectId}/dependencies/{id}/answer`. The request requires `expectedVersion` and a 1..4096 character nonblank answer to an existing USER_ANSWER dependency. An atomic version predicate prevents stale previews and replayed requests from overwriting a newer answer; conflicts use the existing safe HTTP 409 response. Foreign Project IDs cannot reach saved dependencies. Terminal/deadline-expired dependencies cannot be answered. Existing Shell calls retain their original repository overload.

Answer submission stores a redacted answer and the existing durable observation fact, then flushes facts. It does not observe files/network/processes, grant Tool permission, resume a Run or complete a dependency. The existing watcher/explicit observation remains responsible for USER_ANSWER completion and prerequisite gates; administrator opt-in scheduling retains its existing semantics. Reads do not probe sources or dispatch work. This adds no arbitrary endpoint, registration, process control or automatic retry API.

TDD first failed because the controller and versioned answer contract were missing. Real SQLite/controller tests cover stale version, foreign Project, omitted version, separate WAITING state and unchanged accepted answer. HTTP tests use a real authenticated server and SQLite across two application lifetimes: unauthenticated reads/writes are denied, valid submission persists, stale replay is rejected with 409, history is readable and no source inspection is invoked. The scheduled mock tick invoked during application startup is independent of the endpoint; the test specifically verifies absence of endpoint source inspection rather than treating startup scheduling as an endpoint side effect.

Focused validation passed eight tests. Full server regression passed 2936 tests in 561 suites, with 0 failures, 0 errors and 0 skipped. Native answer controls, Goal/Scheduler HTTP controls and the other original audit requirements remain unfinished.

## HTTP usage

With `rei.web.enabled=true`, use the existing API key authentication. These controls are human-facing and are not registered as model Tools.

| Method | Path | Behavior |
|---|---|---|
| GET | `/api/v1/projects/{projectId}/dependencies` | Saved entries, up to 256 |
| GET | `/api/v1/projects/{projectId}/dependencies/{id}` | Saved question, answer, state and version |
| GET | `/api/v1/projects/{projectId}/dependencies/{id}/history` | Saved facts, up to 256 |
| POST | `/api/v1/projects/{projectId}/dependencies/{id}/answer` | Save explicit USER_ANSWER reply |

Read the current entry, review its `spec.target` question, then submit `{"expectedVersion":0,"answer":"Choice A"}` using that entry's actual version. HTTP 200 returns the saved entry. HTTP 409 means the entry changed or is terminal: refresh and review before another explicit decision. Do not automatically retry a POST after timeout; GET the entry and its history to determine whether it was accepted. HTTP 400 covers malformed requests, non-question dependencies and missing or invalid version/answer. The shared API key covers all registered Projects; a Project path is an ownership boundary, not a per-user access-control claim.

