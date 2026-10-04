# Persistent interval schedules

Branch: `codex/persistent-interval-schedules`

`scheduleInterval(interval, occurrences, action, conversationId)` creates an explicitly activated PENDING schedule, scoped to the existing owning Project and Session. Interval bounds: 1 minute to 366 days; occurrences: 2 to 100. `/timer show ID` includes the interval and remaining count. Registration requires LOCAL_WRITE through the existing tool policy.

Intervals use the existing SQLite transaction, claim, FIFO dispatch, permission and recovery paths. Only a successful occurrence schedules another one, starting from its completion time. Missed intervals coalesce into a single due occurrence; no burst replay. Failed, cancelled and uncertain executions never automatically recur. Stale completion callbacks cannot decrement the count or schedule another occurrence. Running schedules require normal Run controls before uncertainty reconciliation.

TDD: compilation failed for the new API before implementation. Focused scheduler, dispatcher and recovery tests passed after implementation. Full regression result recorded after execution below.

Remaining scope: scheduler cron and event triggers; other unfinished audit entries remain open. This feature does not complete the overall request.

Validation: full suite 2,869 tests / 548 suites; failures 0, errors 0, skipped 0. Focused scheduler/dispatcher/recovery suite passed.
