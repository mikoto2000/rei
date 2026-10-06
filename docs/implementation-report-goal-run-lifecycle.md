# Goal Run lifecycle integration

Branch: `codex/goal-run-lifecycle`

When the existing Web RunRegistry and RunService are available, GoalChatGateway now registers each actual attempt as QUEUED before Project FIFO admission and executes it through the common Run lifecycle. Ownership retains the saved Project/root/Session and actual attempt Run ID. Ordinary Chat terminal events update that same registry; independent verification shortcuts and non-Chat stop paths publish a missing terminal event without invoking Chat again. Missing terminal transition and publication share the existing Run monitor with cancellation, so an accepted cancellation cannot be overwritten or followed by a synthesized success. A cancelled registry outcome also stops Goal continuation. Existing Goal budgets, verification and continuation remain authoritative. CLI configurations without Web services keep the existing execution path.

RunService now accepts an application callback for queued cancellation. The callback is removed once execution starts, on admission rollback, or on retention cleanup. It runs after Run cancellation publication and outside the bus monitor, preventing application persistence locks from being acquired under that monitor. Duplicate cancellation does not notify again. A cancellation racing between queue dispatch and lifecycle entry also skips work and notifies exactly once. Callback failure is logged by class and never converts an accepted cancellation into a retry request.

Goal callbacks record a cancelled queued attempt and pause the durable Goal, preserving budgets. Explicit Goal cancellation first persists CANCELLED and uses the same Run controls; its callback cannot revive an inactive claim. Restored or expired Runs missing from the live registry fall back to the existing cancellation path. Synchronous admission failure removes only the newly registered Run and callback, leaving existing identities untouched. Unknown or restored attempts are never automatically registered as executing or replayed.

TDD: missing queued-cancellation registration failed compilation first. A separate race test initially failed because lifecycle-skipped work lost its callback, then passed after the fix. Real FIFO/SQLite/gateway tests cover captured ownership, QUEUED/RUNNING/COMPLETED, no duplicate Chat execution, independent queued verification without Chat, ordinary queued Run cancellation with Goal PAUSED and cancelled attempt, and cancellation of restored Goals without live registry state. Existing full Chat budget tests remain in the focused run.

Full server regression passed 2946 tests in 564 suites, with 0 failures, 0 errors and 0 skipped. Native Goal screen/attachment controls, Scheduler HTTP/Native controls and other original audit requirements remain outstanding.


