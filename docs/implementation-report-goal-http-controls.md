# Goal HTTP controls

Branch: `codex/goal-http-controls`

The authenticated Project-scoped Goal API reuses GoalRepository and GoalLoopService rather than creating another planner or restoration engine. GET list, detail and history/attempts only read saved state. Explicit POST verify invokes independent file verification; because this can mark an already satisfied Goal completed, it is deliberately not a GET. POST run and cancel use existing permission enablement, Project/Session validation, FIFO gateway, bounded budgets and cancellation rules.

POST reconcile requires `expectedRunId` and `acknowledgeUncertainSideEffects:true`, meaning the human has inspected unknown effects. Use the explicit string `none` only for a saved claim without an attempted Run. The service rejects a queued/executing Run and the repository atomically requires the exact saved Run identity and RUNNING state. Successful reconciliation pauses the Goal and marks uncertain attempts blocked without restoring budgets, claiming success or dispatching another Run. A subsequent run remains a separate explicit action. State conflicts return the existing safe HTTP 409 response without revealing internal exception text; invalid/foreign requests use existing 400 handling.

Endpoints under `/api/v1/projects/{projectId}/goals`: GET root, GET `/{id}`, GET `/{id}/history`; POST `/{id}/verify`, `/{id}/run`, `/{id}/cancel`, `/{id}/reconcile`. Example reconciliation body: `{"expectedRunId":"the-observed-run","acknowledgeUncertainSideEffects":true}`. GET after any uncertain mutation before deciding to retry; no automatic POST retry is added. Run responses describe the authoritative Goal lifecycle, rather than inventing an accepted chat receipt or replacing its saved Session.

TDD first failed on missing controller. Controller tests prove reads cannot invoke execution and missing acknowledgment cannot invoke reconciliation; active-Run conflict invokes the service only once. Real HTTP/SQLite tests verify authentication, read-only discovery, explicit bounded dispatch, in-flight rejection, stale Run rejection, exact reconciliation, preserved attempts and no replay. Existing Goal recovery, loop and gateway tests pass alongside the new tests.

Full server regression passed 2939 tests in 563 suites, with 0 failures, 0 errors and 0 skipped. Native Goal controls and integration with generic Run status/SSE tracking remain to be completed. Scheduler HTTP/Native controls and other original audit requirements remain outstanding.

