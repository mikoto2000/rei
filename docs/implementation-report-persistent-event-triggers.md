# Persistent event triggers

Branch: `codex/persistent-event-triggers`

`scheduleOnEvent(sourceRunId, eventType, expiresAfter, action, conversationId)` registers a PENDING one-shot continuation in the exact owning Project/Session. Supported events are `AGENT_RUN_COMPLETED`, `AGENT_RUN_FAILED`, `AGENT_RUN_CANCELLED`, `EXECUTION_COMPLETED`, `EXECUTION_FAILED`, `EXECUTION_CANCELLED`. The source Run ID is exact and bounded to 128 characters; expiry is 1 second..366 days. The tool uses LOCAL_WRITE policy. `/timer show ID` displays the condition, activation time, expiry and matched event ID.

Explicit `/timer activate ID` enters WAITING_EVENT. Only facts timestamped after activation and before expiry can make it SCHEDULED. Waiting is not time-based dispatch. Cancellation and expiry are terminal. A matching event causes one atomic transition; duplicate live/replayed facts cannot create another run. The action uses the existing durable claim, captured owner, FIFO, scheduler enable flag and enforced permission configuration. Uncertain claimed execution is not replayed.

The service subscribes to the existing Agent Event Bus and unsubscribes on shutdown. Restart recovery reads the existing Project event log through persistent byte cursors, rotating through at most 8 waiting Projects per tick. Each Project page reads at most 256 KiB / 128 lines, accepting lines up to 64 KiB. Partial records are preserved; oversized lines are discarded across page boundaries without parsing fragments. New activation invalidates old cursor writes with a generation check. Replay checks the file Project against event ownership. Decoder warnings omit raw malformed contents.

Expiry limits waiting, not completion after a valid match. Events before activation are intentionally ineligible. Recovery requires an available compatible persisted event; malformed/oversized/absent facts cannot prove a match and the wait eventually expires. Arbitrary API/filesystem/process polling conditions belong to the separate dependency watcher work.

TDD: new API compilation failed before implementation. Focused integration verifies exact ownership, explicit activation, duplicate/concurrent delivery, cancellation/expiry, restart/uncertain claims, paginated restart replay, stale cursor rejection, real dispatcher/FIFO admission and subscription disposal. Page tests cover ordering, limits, interrupted/oversized records and truncation. Full regression result recorded below after execution.

Remaining: other open audit entries, including generic dependency watchers. This change is not overall completion.

Validation: full suite 2,887 tests / 551 suites; failures 0, errors 0, skipped 0. Event trigger integration: 10 tests; event pagination: 3 tests.
