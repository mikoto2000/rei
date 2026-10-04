# Persistent cron schedules

Branch: `codex/persistent-cron-schedules`

`scheduleCron(expression, zone, occurrences, action, conversationId)` registers a PENDING schedule scoped to the current Project and exact Session. The user reviews `/timer show ID` (cron, zone, remaining count) and activates it through existing controls. The tool uses the existing LOCAL_WRITE policy.

Six-field Spring cron expressions are accepted with seconds fixed to `0` (minute granularity), explicit IANA time zone and 2..100 occurrences. Configuration is validated before inserting any rows; the next occurrence must be within 366 days. Calendar computation handles daylight saving gaps through the existing Spring cron engine.

SQLite stores the expression, zone and occurrence budget alongside the existing schedule. Restart preserves activation and due time. A due occurrence coalesces missed dates, and only successful completion schedules a future occurrence strictly after completion. Failed, cancelled or uncertain runs do not repeat. The existing atomic claim, captured project/session, FIFO dispatch and manual uncertainty recovery remain in force. Old callbacks cannot consume another occurrence or resurrect a schedule.

Interval schedules now decrement their remaining count on the final successful occurrence too, so completed intervals report zero remaining.

TDD: new API compilation failed before implementation; focused scheduler/dispatcher/recovery/policy tests passed afterward. Integration coverage includes Japanese wall-clock time, a US daylight saving gap, restart/coalescing, stale callbacks, terminal states, exact session ownership and invalid configuration rollback.

Remaining: event triggers and the other open audit entries. This feature is not overall completion.

Validation: full suite 2,874 tests / 549 suites; failures 0, errors 0, skipped 0.
