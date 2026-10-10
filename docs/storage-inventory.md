# Storage Inventory — Phase 1

Research baseline: `4736f06cf8495f88e74136673c4d5311c8d8c130` (origin/main, 2026-10-10).

## Current persistence and compatibility constraints

| Data | Current implementation | Scope / constraints |
| --- | --- | --- |
| Sessions | `FileSessionRepository` | Data-directory `sessions.json`; whole-file atomic replacement; `accept` rolls back metadata if admission fails; immutable ID/project/title/creation time |
| Turns | `ConversationTurnStore` | Global or project `state/turns/<name-UUID>.json`; whole conversation arrays, cached in memory; creation time advances on clock ties/rollback; conversation key must remain recoverable without reversing the UUID |
| Agent events | `ProjectAgentEventStore` | Project `events/events.jsonl`; already contains durable project sequence; ID and payload must remain unchanged |
| Scheduler replay | `AgentEventTriggerService`, `PersistentAgentScheduler` | Consolidation DB stores byte offset, oversized-line discard flag, and generation; event signalling precedes compare-and-set cursor advancement |
| Raw results | `RawToolResultStore`, `ContextFiles` | Global/project `state/context/results/<conversation-key>/<result-key>.json`; result reference is a deterministic name UUID; raw content remains a file |
| Checkpoints | `PersistentCheckpointRepository` | Existing consolidation SQLite; heads, revisions, event deduplication keys; preserve revision/lease semantics and existing limits |
| Artifacts | `ArtifactStore` | Metadata in existing SQLite and bodies in `artifacts/*.bin`; size/count limits; refresh can change AVAILABLE to EXPIRED, so inventory must not call refresh |
| Context revisions | `WorkContextRepository` | Existing SQLite revision/head tables |
| Memory / vectors | Existing datasource configurations | Keep existing memory, consolidation and vector DB schemas; vector datasource requires extension support |

`ReiDataDirectory` resolves the configured OS data directory. `ProjectStorage` resolves
`projects/<UUID>` below it. Custom output paths can be outside those roots and cannot
be assumed to be inventoried or safe to delete.

Event replay currently reads at most 256 KiB / 128 lines per page with a 64 KiB line
cap, retaining an incomplete normal final line. Malformed records are currently
logged and skipped by the existing reader. Migration must reject or explicitly
resolve such records rather than copy that skip policy. An offset that points into
a line and the discard flag need explicit migration fixtures. Event sequence already
exists; sorting by timestamp would break the existing order. Cursor advancement and
external effects do not establish exactly-once external delivery.

Session repositories use a global catalog, while turns and events can be project
scoped. A future database decision must compare the global catalog with per-project
DB transactions and backup boundaries rather than assuming all data is project-local.
Phase 1 introduces no storage schema or migration coordinator.

## Startup investigation

Repository constructors initialize schemas and session snapshots. Attention,
reflection and profile subscribers start during `@PostConstruct`; the event trigger
subscribes in its constructor and replays on `@Scheduled`. A future migration gate
must precede datasource/repository initialization and subscribers, including both
Shell and Web paths. An `ApplicationRunner` alone is too late.

## Read-only observation

`/storage status` scans the data root. `/storage status --project current` scans the
current operation's storage root. The scanner does not create directories, refresh
repositories, hash files, checkpoint SQLite, or delete anything.

Default limits are 20,000 visited filesystem entries, depth 32 and 16 MiB of JSON
content per measurement. Links are not followed. JSON arrays/objects are streamed
instead of materialized. Unknown paths, unavailable record counts, incomplete or
malformed records, depth limits and exhausted budgets are explicitly reported.
File size denotes file length, not allocated disk blocks. SQLite rows, logical bytes
and free pages are not yet measured in Phase 1. WAL and SHM file lengths appear
separately. Concurrent writers can make observations inconsistent; these results are
not a migration validation or a coherent backup.

Growth is measured against the previous complete scan of the same root in this
process (up to 64 roots). The first scan has no delta. A clock tie or rollback has
no rate. An incomplete scan clears the previous sample so partial totals cannot
produce a misleading growth estimate. Samples are not written to user storage.

Reference protection and retention candidates are unverified in Phase 1. No
retention execution, approval, auto-deletion, automatic migration, backup, or restore
is implemented. Existing application retention mechanisms are unchanged.

## Verification record

The initial four inventory tests were added before implementation and failed
compilation because `StorageInventory` did not exist. After the implementation,
`mvnw.cmd test -Pfull -Dtest=StorageInventoryTest -q` passed all four tests.
The newline-boundary test then failed (expected unknown, observed one record) before
adding the final-newline check. The final targeted run passed 5 tests, 0 failures,
0 errors, 0 skips. A baseline run invalidated by concurrent Maven compilation is
excluded from regression evidence.

## Regression prerequisite and final verification

A serialized baseline regression exposed a javac plugin-discovery bottleneck in
`RepositoryMapService`; PR #71 fixed it without relaxing scan deadlines or test
assertions. Its full Java regression passed 4,392 tests, 0 failures, 0 errors,
1 skip, and Java CI passed. Phase 1 includes that fix at main `337af398`.

After restoring this draft, inventory (5), compiler isolation (2), and impact (6)
tests all passed. The project-scope guard was then added with Red/Green: absent
project selection must return exit 2 instead of silently measuring global data.

React `npm test` passed 25 files / 99 tests. Native `cargo test --locked` passed
120 tests and 0 doc-tests. Browser E2E and native GUI checks were not performed.
No real user data was modified for testing. Phases 2–6 are separate later changes.

Local evidence is retained under ignored `target/`: inventory Red/Green logs,
`storage-command-red.log`, `storage-phase1-green.log`, and
`repository-map-fix-evidence/`. CI runs the full Java suite before this phase merges.