# Storage backup and restore

Phase 2 stores snapshots under `.storage/backups/<time>-<UUID>/` below Rei's data
directory. `files/` mirrors relative source paths, `manifest.jsonl` records file
lengths and SHA-256 hashes, `snapshot.json` records the source fingerprint, and
`verified.json` binds the verified manifest and snapshot. Backups and old files are
never automatically deleted. They can contain private conversation and tool data;
keep the directory's existing user access restrictions.

Phase 3 additionally records `legacy-snapshot.json`, excluding only the new target
`storage.db`. Its fingerprint is bound by `verified.json` and checked again after
row import, before commit. Older Phase-2 backups remain independently verifiable
and restorable without this additional snapshot.

The foundation backs up migration-relevant sources: `sessions.json`, `projects.json`,
the existing `storage.db`, `memory.db`, `memory-consolidation.db`, and global/project `state`,
`events` and `artifacts`. The consolidation DB includes Checkpoint and Scheduler
metadata; state includes their raw results and persisted execution metadata.
Memory is included because its existing startup helper can update its schema after
the gate. The coordinator does not modify that database. Unchanged Vector DBs,
voice models, images, paper originals, rotating logs,
worktrees and external exports are not transformed by Phase 2 and are outside this
backup set. Later migrations must extend the set if they change those sources.

SQLite files are recognized by `.db` / `.sqlite` / `.sqlite3` extensions or their
16-byte SQLite header, so unusual filenames retain committed WAL rows too.
SQLite snapshots use Xerial's SQLite Online Backup API through a read-only source
connection. Committed WAL contents are included in the destination DB; WAL/SHM are
not copied as independent databases. Destination integrity is checked. Orphan WAL
files are an unresolved source and stop migration. Source-file fingerprints are
checked again before the switch, including WAL contents. Stop all old Rei processes
before upgrading; older binaries do not honor the new instance lease.

Files are copied and hashed with bounded buffers. Manifests are streamed with
bounded line/metadata lengths; links, special paths, path escapes, unknown project
folders and more than one million source entries stop preparation. The free-space
preflight reserves three times the selected source bytes plus 64 MiB for backup
and subsequent import. This is an estimate: actual write failures still abort.
Large initial backups can take time; ready startup does not repeat them.

## Recovery

If startup fails, retain the old data and all backups, close old processes, inspect
the logged failure and `.storage/migration-state.json`, fix the reported storage
problem, then restart. A copied backup lacking `verified.json` is incomplete and
must not be treated as a successful backup. Corrupt legacy records will need repair
before the later import phases; Phase 2 does not silently parse or discard them.

An owned interrupted `storage.db` transaction is recovered automatically only
after validating its state and backup. Missing/invalid state, a corrupt backup,
an unknown original target or a newer header stops startup. Do not delete a hot
journal manually; it can contain the original pages needed for recovery.

`StorageBackup.verify` validates a backup independently of current source contents.
`restoreToEmptyDirectory` restores and verifies it only into an empty directory;
it refuses an existing populated target. This foundation API is tested but no
interactive restore command is introduced in Phase 2. Do not overwrite a live data
directory or roll back after newer data has been written. Restore to a separate
directory and review newer data first; subsequent operational commands will retain
this restriction.
