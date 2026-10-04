# Scheduler Run lifecycle

Branch: `codex/scheduler-run-lifecycle`

Status: Implemented. Merged into `main` after validation; commit identities are recorded in Git history.

The real scheduled execution path optionally joins the existing Web RunRegistry and RunService when both beans are present. CLI contexts without those beans retain their existing execution path. The actual durable claimed Run ID is registered QUEUED before Project FIFO admission, using the exact saved Project and Session. Starting work transitions the same Run to RUNNING; actual completion, failure or cancellation produces its terminal state and event. Missing application terminal events are filled through the shared lifecycle without duplicating an event already emitted by Chat.

Normal Run cancellation of a queued schedule completes the durable claim as CANCELLED and releases the dispatcher. Cancellation arriving after executor admission but before work begins also skips Chat and completes the saved claim. Already cancelled Runs cannot be reclassified successful by a later result. Durable callbacks use RunService's existing notification boundary outside the event bus monitor. Synchronous admission failures remove only a newly registered Run and its queued cancellation callback.

Existing scheduler opt-in, exact Project/root/Session checks, bounded repeats, missed-time handling and FIFO dispatch remain in place. Cancelled or failed occurrences do not schedule a successful repeat. Restored uncertain RUNNING records are not automatically registered or replayed; explicit reconciliation still checks the saved Run and current in-flight state.

TDD first failed on the missing tracking configuration method. Real FIFO, RunRegistry, RunService and SQLite tests verify QUEUED/RUNNING/COMPLETED with saved Session, ordinary queued cancellation with no Chat execution and a released dispatcher, cancellation before worker start, and a single failure terminal event without repeated Chat execution. Existing scheduler dispatch, recovery, authenticated HTTP and shared queued-cancellation tests also pass.

Full offline Maven `-Pfull test` passed 2973 tests in 569 suites with zero failures, errors or skipped tests. Result: PASS. Post-merge dispatcher/recovery/HTTP/shared-cancellation tests are run before pushing main.

Remaining: Native Scheduler recovery controls and other incomplete original audit requirements.
