# Task and Run reflections

Branch: `codex/task-run-reflections`

The existing verified Goal reflection remains available. Owned Task completion/failure and Agent Run completion/failure/cancellation now also produce structured SQLite reflections without any extra LLM call. Records include source identity, expected Task title when known, actual completed/failed Tool call facts for Runs, declared terminal state, observed error type, expectation gap, a next review action, reusable review candidates and source Event ID. Tool/exception bodies and model prose are excluded. Titles, Tool names and error types are redacted and bounded.

A normally ended Run records TASK_CRITERIA_NOT_DECLARED; it does not pretend that the user's desired outcome was independently verified. Task completion records TASK_COMPLETION_REPORTED, distinguishing the task-state update from outcome verification. Existing Goal reflections retain independent file-digest evidence. Task records do not copy other Tool actions from their parent Run. Failure types are observed facts, not inferred root causes. Review candidates retain specific failed Tool/call identities and are not automatically promoted to trusted long-term memories.

Task Tools now publish actual create/start/complete/fail facts. taskComplete requires the returned Task ID and DONE state before reporting completion. Failed or unconfirmed operations publish failure and rethrow; notification delivery failure does not turn an already successful external operation into a retryable failure. Tests use the existing TaskService boundary and do not call Google Tasks.

Run action facts are deduplicated and capped at 257 saved rows, exposing 256 and an explicit truncation flag. Facts survive restart before termination. Final reflections are immutable and deduplicated by Project/Session/source kind/source ID/status. Project-scoped list/show are capped at 256 records. /reflection runs and /reflection run ID inspect them; existing Goal list/show/collect remain compatible.

/reflection collect-run RUN_ID is a human backfill of persisted Event facts for the current Project and Session. It never executes or verifies the Run. It requires an exact owned terminal fact in the last 1,000 Project events and rejects a full window rather than presenting partial history as complete. Existing live reflections remain authoritative on repeated collection. Task changes outside Rei's owned Task Tool execution are not observed by this in-process service.

TDD: missing persistence/service, Task producer and Shell/backfill APIs failed compilation before implementation. Focused tests passed across fact deduplication, null verification claims, failure redaction, ownership, database reopen, overflow, scoped Shell reads and persisted-event backfill. An integration test connects an actual Task Tool result through the Event Bus into SQLite reflection and rejects an unconfirmed OPEN Task as completion.

Other audit entries and optional semantic analysis/validated-memory promotion remain open; this feature is not overall completion.

Validation: full Maven suite passed: 2,930 tests / 559 suites; failures, errors and skipped = 0. git diff --check passed.
