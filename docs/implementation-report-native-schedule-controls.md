# Native Scheduler controls

Status: Implemented

Branch: `codex/native-schedule-controls`

Merged into: `main` after validation; commit identities are recorded in Git history.

Native Recovery now shows Project-scoped saved schedules with Session, state, due time, action, outcome, history and interval/cron/event metadata. Explicit confirmation precedes activation and unclaimed cancellation. Activation preserves the server's existing Scheduler/permission opt-in gates. Uncertain RUNNING reconciliation additionally requires a checked acknowledgement and the exact observed saved Run ID. It marks the existing uncertain claim failed without replaying it or inventing success. Creation remains through existing scheduler Tools/CLI.

Rust adds typed allowlisted schedule operations using existing authenticated transport. List/history output is bounded, ownership/state and mutation receipts are checked, and malformed activation/cancellation/reconciliation receipts are rejected. No arbitrary URL or local filesystem access is introduced. Runtime-side `schedule_track` reads the saved schedule and actual Run, verifies Project/Session identity and registered Project, and reuses the shared saved-Run projection/SSE path. It preserves the actual Run ID and saved Session, avoids duplicate projections and never submits execution. Restored/expired unavailable Runs are not automatically registered or replayed.

The UI clears previews on Project/server changes, ignores stale reads, prevents duplicate pending actions and never automatically resends a failed POST. Accepted activation may attach the actual current Run if dispatch has already occurred; otherwise the user refreshes and explicitly tracks the saved Run later. Failed tracking does not retry activation. Older server endpoint errors remain confined to the schedule panel.

TDD first failed on missing Rust operations/snapshot method and React component. Three Rust HTTP/native-application tests cover authenticated controls, detail/history metadata, exact acknowledgement, receipt state, stale Run rejection, foreign Session rejection, read-only actual Run attachment and duplicate tracking. Four React tests cover explicit confirmation/acknowledgement, accepted Run attachment, failed-mutation non-retry and late Project response isolation. Four desktop/mobile E2E cases cover activation review/attachment and uncertain reconciliation; screenshots were inspected and buttons fit above the mobile navigation without horizontal overflow.

Tests: full Rust offline suite and native feature check passed; 67 React tests in 20 files passed; TypeScript, ESLint, Prettier and production build passed; 28 desktop/mobile E2E tests passed. Initial E2E completion stalled during test-server shutdown and was interrupted; the repeated run completed with exit 0 after terminating only its identified test Vite process. Final receipt guard was rechecked with schedule/Goal/checkpoint tests and native check. Real Java HTTP/SQLite integration also verifies date fields use the string wire shape consumed by Native. Result: PASS. Focused post-merge tests are run before pushing main.

Remaining: other incomplete original audit requirements, including Activity attribution, External delegation continuation/fix, semantic self-review and Priority D features.
