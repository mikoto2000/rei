# Storage migration foundation (Phase 2)

Starting the application initializes `storage.db` automatically; no SQL or
migration command is required. In Phase 2, legacy repositories remain active and
historical records are not converted. Subsequent phases will use this gate for
the Session/Turn and Event/Cursor imports.

The startup sequence is: acquire instance lease, read version, determine need,
copy required sources, verify copied hashes / SQLite integrity / source stability,
write a verified backup marker, initialize schema transactionally, commit the
completed migration marker and version, then allow ordinary beans to start.

`storage_migrations(version,status,backup,completed)` records the completed schema
transition in the same transaction as `user_version`. `.storage/migration-state.json`
records COPYING, VERIFIED, APPLYING and COMPLETE progress using forced temporary
files and atomic replacement. This journal is diagnostic; committed SQLite schema
and completion records are authoritative. A crash before commit causes a fresh
backup and retry on the next startup. Partial and completed backups are retained.
An unknown unversioned DB with pre-existing tables is rejected.

A spilled uncommitted SQLite transaction can leave a hot rollback journal. A
read-only version probe cannot roll it back. Recovery opens only the new
`storage.db` read/write, and only after checking the matching APPLYING journal,
source version, original-database identity and independently verified backup.
The original version-zero target must have been absent or empty. Newer headers,
unrecognized state and corrupt backups stop recovery without changing the DB or
rollback journal. Existing Memory / consolidation DBs are not recovered or written
by this path. Same-JVM shared leases also recheck version/completion before each
new context starts.

Backups are made only when a schema transition is needed and relevant sources
exist. Repeated ready startup neither copies nor reimports records. Failure stops
normal startup and logs the root, stage and backup location without source content.
Old files are retained; there is no fallback that would discard post-switch data.

## Verification

Tests use temporary directories and temporary SQLite databases. Red/Green covers
fresh initialization, lightweight restart, legacy-byte preservation, WAL-only
committed rows, verified restore to an empty directory, backup corruption,
concurrent source mutation, injected disk/write failure, interrupted application,
exclusive leases and early Spring startup failure. A child JVM is forcibly killed
inside the schema transaction after DDL and before commit, then retried.
Physical power loss and real disk exhaustion are not induced on the user's machine;
the tests exercise termination and write-failure boundaries. Durability still
depends on the filesystem and drive honoring forced writes and SQLite commits.

An initial full regression ran 4,432 tests with one existing
`ToolsTest` timing failure and one optional skip. Isolated retries passed that
unchanged assertion. The early-exit fixture's Windows shell was then changed to
cmd.exe: measured startup/exit was 20–31 ms versus PowerShell's 588–673 ms in a
quiet retry. The one-second observation window and expected exit 9 / foreground
failure assertions are unchanged; production command behavior is unchanged.
Full regression after the recovery guards and fixture change passed 4,437 tests
with zero failures/errors and one optional skip. Additional Red/Green checks cover
the existing Memory database and SQLite WAL snapshots with unusual filenames.
The final storage-only run passed 33 tests (21 coordinator cases, 3 startup gate
cases, 6 inventory cases and 3 command cases), with zero failures/errors/skips.
