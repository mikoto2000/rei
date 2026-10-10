# Approved retention execution

Schema 5 adds `retention_executions`, `retention_execution_items`,
`retention_purge_approvals`, `activity_segments`, `retention_automatic_consents`
and `retention_automatic_runs`. Startup backs up and verifies the older database
and source files before adding tables. It never enables retention. Memory,
consolidation, VectorStore and Artifact schemas and existing limits are preserved.

## Manual operation

```text
/storage status
/storage retention plan --category activity-raw --project current --max-objects 100 --max-bytes 16777216
/storage retention approve <plan-id>
/storage retention apply <plan-id>
/storage retention history
/storage retention restore <execution-id>
```

Omitting `--project` selects global objects only. Missing current project is an
error. Approval binds scope, policy version, revisions, references, flags and
hashes. Apply rechecks them before committing an execution journal and DELETING
state. The shared root writer excludes reference admission during this transition;
later pointers to unavailable bodies are rejected. New pins, legal holds and
reference guards stop mutation, including after restart.

The physical collector accepts **closed application-produced Activity diagnostic
archives only**, revalidating UUID layout and identity. Raw results and Events
remain `reference-check-incomplete`; absent known edges cannot prove safety.
Reflection without a proven collector is not collected. This conservative limit
can retain substantial data. No age/count/byte cap bypasses protection. Old
Activity records in memory.db, Session/Run-associated logs, old sources, exports,
models and worktrees remain retained; the feature does not claim complete coverage
of all historical reference sources.

Bodies move without overwrite to `.storage/quarantine/<execution-id>/<ordinal>.body`.
Each execution has at most 1,000 items and its approved byte budget (at most 1 GiB).
Quarantine retains bytes and does **not** reclaim disk space. History reads the
latest 100 summaries. Audit tombstones remain; status excludes DELETED body bytes
and includes quarantined bytes. Metadata counts include tombstones.

Restore verifies all bodies and refuses a reappeared original. Retry the same
apply/restore after interruption. Real child-process tests kill execution after
move, restore and removal, then recover from the journal. Both paths present,
both missing before authorized removal, changed content or unsafe paths stop
for inspection. Files are forced and SQLite uses FULL synchronization, but
power-loss durability depends on the filesystem/hardware: unexplained missing
bodies are never treated as success. Do not remove conflicts to bypass checks.

After **seven days** in quarantine, permanent removal needs separate approval:

```text
/storage retention approve-purge <execution-id>
/storage retention purge <execution-id>
```

Purge approval binds the current quarantine snapshot and revisions. Guards and
hashes are rechecked before durable PURGING state. Only previously authorized
PURGING items accept a missing body on retry. A new protection invalidates approval;
after protection is resolved, an interrupted authorized purge can receive fresh
explicit approval. Restore is unavailable once permanent deletion has begun.
These commands never purge old sources or migration backups.

## New Activity production

Production ProfileEventLogStore delegates new summaries to ManagedActivityLog.
Existing `logs/activity.jsonl` is read but never adopted, rewritten or truncated.
Global/project UUID files under `logs/activity-archives/` close at 4 MiB or when
the next append sees a segment at least a day old. At most one segment is open
per scope; open segments are not candidates. Records are limited to 1 MiB.
Append updates a per-record hash chain without hashing the whole prefix. Closure
verifies the chain once and publishes body hash and references atomically.
Interrupted writes and same-size external changes prevent adoption.

Every Session and Run association is retained as a reference, including unknown
owners: inactivity is never inferred from an absent running Turn. Unregistered
files are unmanaged and never collected. The materializing history API caps
reads at 32 MiB, 100,000 rows and 10,000 archives, with 1 MiB lines. Corrupt UTF-8,
JSON, partial tails and limit overflow fail explicitly. Legacy constructors retain
their existing fixture/API mode. Scheduler's authoritative facts remain separate
in agent_events.

## Prospective automatic consent

Default ages remain raw results 30 days, Event/Activity 90 and reflection 365;
Session, Turn, conversations and vectors are indefinite. All automation starts OFF.

```text
/storage retention policy --category activity-raw --retention-days 90 --max-count 10000 --max-policy-bytes 1073741824
/storage retention auto propose --category activity-raw --project current --max-objects 100 --max-bytes 16777216 --interval-seconds 86400
/storage retention auto approve <consent-id>
/storage retention auto history
/storage retention auto disable <consent-id>
```

Proposal prints scope, category, policy/version, exact conditions, maximum count
and bytes, frequency and snapshot; it is inert until approval. Only the proven
Activity collector supports automatic execution. Each minute handles at most one
due consent, reserves its frequency, creates a fresh bounded plan, revalidates
consent, records provenance and quarantines the approved snapshot. Errors stop
that attempt and are logged. **Automatic execution never purges permanently.**
Policy changes disable automation and invalidate previous proposals/approvals;
new conditions require new consent. Migration success grants no consent.

## SQLite physical space

```text
/storage maintenance status
/storage maintenance checkpoint
/storage maintenance incremental-vacuum --pages 100
/storage maintenance vacuum --window-seconds 300
```

Read-only observation reports root DB/WAL, known journal size, page/free-page
counts, estimated used page bytes and disk free space. Used pages include indexes
and SQLite overhead. Other DBs and arbitrary SQLite temporary locations are outside
this maintenance scope. Status hashes no bodies and performs no checkpoint.
PASSIVE checkpoint reports frames blocked by a reader. Incremental vacuum accepts
1..10,000 pages only when existing auto_vacuum is 2; that mode is never changed.
Explicit VACUUM requires a 1..3,600-second window, no active Turns, complete
checkpoint and free space of twice DB size plus WAL plus 64 MiB. It serializes
writers and installs deadline/interruption handling. Busy/space/interruption
failures can be rescheduled. There is no always-on or capacity-triggered VACUUM.
Logical row removal and physical space recovery are distinct.

## Backup and recovery

Migration snapshots now include global/project Activity raw/archive files and
quarantine bodies with existing JSON/JSONL, raw results, relevant SQLite and
Artifact sources. Live rei.log is not a migration source. Online Backup includes
committed WAL; source fingerprints, copy hashes and SQLite integrity are verified.
Backups remain `.storage/backups/<timestamp>-<UUID>/`; preflight requires three
times selected source size plus 64 MiB and actual ENOSPC fails safely.

Stop legacy applications/other writers before upgrading. Failure stops ordinary
startup and preserves sources and the logged failure-stage backup. Retry starts a
new verified snapshot and transaction. StorageBackup.verify and
restoreToEmptyDirectory recover to a **separate empty directory**. Never overwrite
post-migration writes; preserve both directories until new records/references
are reconciled. No SQL or migration command is required for normal upgrading.

All destructive verification uses temporary data. Tests cover approval/guard
changes, scopes, budgets/frequency, policy invalidation, CLI wiring, legacy source
preservation, hash-chain corruption, backup restore, actual process kills,
Reader-held WAL and explicit VACUUM space reclamation.
