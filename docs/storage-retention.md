# Storage references and retention

Phase 4 upgrades `storage.db` to version 3 under the existing startup gate and
verified backup transaction. `stored_objects` records a file's relative path,
SHA-256, recorded size, physical project scope, conversation hash, Run, creation
time, ownership verification, availability, pin, legal hold and revision.
Bodies remain files. `object_references` records typed edges, including Checkpoint
references and placeholders for a body that has not been registered yet.

Existing raw result files are indexed without changing their bytes. Their filenames
do not prove original ownership or creation time: these entries remain unverified
and protected. Unknown files that cannot be identified remain outside the managed
object set and cannot become deletion candidates. New production raw results are
forced to disk and registered before their reference is returned. Reusing an old
file does not upgrade unverified ownership. Application producers share the storage
writer lock; legacy executables must remain stopped during the upgrade.

Checkpoint revisions are read from the consolidation DB with bounded snapshot
sizes, and their explicit raw-result and Event references are registered in the
new DB. Unsupported or malformed snapshots stop migration without changing the
source. New Checkpoint saves register references before committing a new revision
in their existing DB. A failed or pruned revision can leave extra protection:
references are conservatively retained rather than removed without proof that all
owners have stopped using the body. Existing Checkpoint limits and recovery remain
unchanged. A missing referenced body is not fabricated or overwritten.

## Planning and approval

```text
/storage retention plan --category raw-results
/storage retention plan --category activity-raw --project current --max-objects 100 --max-bytes 16777216
/storage retention approve <plan-id>
/storage retention history
```

Omitting `--project` selects **global** objects; it does not silently include project
objects. `--project current` requires a selected project. A plan persists its exact
scope, policy version, candidate metadata and snapshot hash. Candidates are stored
as separate rows in `retention_candidates`; plan history reads metadata only.
`retention_approvals` stores the approved snapshot and time. Approval rechecks
policy version, object revision, references, pin, legal hold, size and file hash.
A changed candidate requires a new plan. Planning and approval never move, truncate
or delete files. Physical execution is introduced in Phase 6.

The planner scans at most 1,000 indexed objects, returns at most 1,000 candidates,
and hashes at most the requested candidate byte budget (maximum 1 GiB). It reports
whether the scan or candidate budget limited the result. A partial scan is not a
claim that all data was considered. History returns the latest 100 plan summaries;
displaying references is limited to 10,000 edges and fails rather than claiming a
larger list is complete. Age limits and optional count/byte caps identify candidates;
protection always takes precedence. Policy changes invalidate outstanding plans.

## Defaults and protection limits

Automatic retention is disabled for every policy. Raw results use a provisional
30-day age, Events and Activity raw 90 days, and reflection 365 days. Session,
Turn, conversation text and vector memory have no expiration. Voice models,
worktrees and exports have no automatic retention. This phase does not change
ArtifactStore's existing TTL or limits, Activity/consolidation repositories,
models, worktrees, exports, source files or backups.

Unverified origins, unavailable bodies, pins, legal holds and reference edges are
protected. Running Turns protect their raw results. An absent explicit edge does
not prove that Memory, summaries, working state, Session or other callers have no
reference: **all raw results whose complete reference graph cannot be proven are
additionally protected as `reference-check-incomplete`**. This is deliberately
conservative and may leave old raw files uncollectable. Do not infer safety from
mtime, an absent Checkpoint edge or an expired age. Future collectors must prove
their reference coverage before relaxing this guard.

`ACTIVITY_RAW` registration is an internal producer contract for a closed,
application-owned UUID archive under `logs/activity-archives/`, never an arbitrary
CLI path or existing unowned archive. Production rotation and collection follow
in Phase 6; this phase does not adopt or delete existing Activity logs.

`/storage status` adds bounded, read-only indexed object counts, recorded body
bytes and protection reasons. Recorded metadata may differ from external file
changes; this status does not hash bodies or authorize cleanup. Its protection
scan limit and the filesystem inventory's unknown/unscanned data remain explicit.

## Verification

Tests use temporary directories, SQLite and fixed clocks. They cover byte-preserved
legacy raw files, active Runs, Checkpoint import and save ordering, unknown ownership,
bounded dry-run, count/byte quotas, exact approval, changed references/files/policies,
pins, legal holds, restart, commands and missing project scope. Schema-2 upgrade
does not reimport stale Session/Turn JSON. An interrupted metadata transaction
rolls back and retries; corrupt Checkpoint sources stop migration and remain intact.
Owned hot journals from interrupted schema-1 or schema-2 migrations can also be
recovered after verifying the older target version, original version and backup;
unknown or future migration states remain rejected without changing the journal.
See the Phase-4 evidence directory for Red/Green and final regression logs.
