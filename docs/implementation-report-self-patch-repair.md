# Self patch saved repair cycle

Status: Implemented (explicit bounded saved repair loop)

Branch: `codex/self-patch-repair-cycle`

Merged into: `main` (commit identities are recorded in Git history)

Implemented:
- Existing test/static diff review cycle, exact current patch check, saved Change Set Apply, post-Apply receipt/content/progress check, and complete test/review/final-test repetition.
- One to three unique explicitly supplied repairs; at most four whole rounds/eight test executions within a single shared 180-second deadline. Existing per-test timeout stays 1..60 seconds.
- Preserve original failed diagnosis, every round and compact applied/unknown receipts; never silently replace initial failure with later success.
- Stop on incomplete/timeout/log omission, changed patch, rejected/unknown receipt, failed application, exhausted repairs or no patch progress; cancellation propagates.
- Reuse Project/root ownership, existing saved proposal hash/preconditions/claim, editing Event/cache boundary and arbitrary-command Policy. No additional LLM/External Agent or child-agent permission.
- Existing selfReviewPatch API and standalone verification remain compatible.
- Audit table corrected to reflect already merged Native Goal/Checkpoint/Scheduler recovery.

Tests:
- Red: missing repair service compilation and missing registered selfRepairPatch callback confirmed.
- Six repair service tests cover ordered failure/fix/recheck, shared deadline, initial diagnostic preservation, changed/incomplete/uncertain rejection, no progress, plan bound, cancellation, opacity of failed tests and unknown outcomes.
- Real Git/SQLite/real Shell test: whitespace finding → saved proposal Apply → clean whole cycle → final test; exactly three commands and unchanged Git index.
- Tool schema/Policy/unchanged SubAgent restriction test passed.
- Existing static review, text Change Set and related regressions passed.
- Full profile: 3,006 tests / 573 suites, zero failures/errors/skips.
- Diff check passed; merge-time focused regression runs before main Push.

Result: PASS

Remaining:
- The parent Chat prepares semantic repair content through existing tools; this service does not invent repair proposals or prove semantic correctness/test coverage.
- Semantic free-text review, external Agent fix/CLI session continuation, diagram/Document Agent and other audit items remain incomplete.
- Filesystem/DB calls have checks before/after stages rather than an atomic distributed transaction or guaranteed immediate interruption.
