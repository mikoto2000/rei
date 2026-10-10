# Session and Turn storage

Phase 3 upgrades the dedicated `storage.db` to schema version 2 before ordinary
Spring beans start. `sessions` holds one metadata row per Session; `turns` holds
one row per original Turn ordinal within project/conversation scope. Search and
mutation keys (IDs, scope, status, seconds/nanoseconds and ordering keys) are SQL
columns with indexes. Each row retains its own typed JSON record, including
response style, source information and metadata; no entire conversation array is
stored in one row. Existing Memory, consolidation, Event and Checkpoint schemas
are unchanged. SQLite connections are short-lived with a five-second busy timeout
and full synchronous writes; the new DB uses WAL for concurrent history readers.

## Automatic import

Both fresh installations and version-1 installations use the same startup gate.
It takes an offline verified backup before opening the schema transaction. Old
`sessions.json` and global/project `state/turns/<UUID>.json` arrays are parsed one
record at a time. The historical filename hash is retained as the conversation
key; uncatalogued conversations do not require guessing an original conversation
ID. Project UUID scopes are canonicalized; duplicate source scopes/keys fail.

Counts and SHA-256 digests of canonical records read back from SQLite must match
the imported stream. `storage_imports` retains original file hashes, counts and
row digests. Duplicate Session IDs, invalid identities/statuses, broken/trailing
JSON, unknown source files, changed sources and verification failures roll back
all imports. A second fingerprint excludes only the coordinator's target DB and
checks every selected legacy source again before commit. The row data, import
receipts, version and completed marker commit together. No source is deleted.

Known `turns-<digits>.tmp` files from interrupted legacy atomic writes are inactive
temporary files: preserve them in place and in backup, record their hash and zero
published rows, and log that they were retained. They are not promoted to committed
Turns. Other unexpected files stop migration. Ready version-2 startup validates
the required columns and marker without rescanning old files; it never reimports
old JSON over newer rows. Import is limited to one million rows per source array,
and the existing bounded backup path/count/metadata limits remain in force.

## Compatibility

Spring's Session and Turn beans now use SQLite. Legacy file implementations and
the in-memory Turn fixture remain available for existing callers and tests.
Session admission commits before queue dispatch and restores the prior row if
synchronous dispatch fails. A shared writer monitor and revision checks prevent
overwriting a newer admission during rollback. Identity/title/creation time remain
immutable; update time is monotonic. Completion reads an already loaded cache of
at most 101 metadata rows and performs no I/O.

Turn lifecycle, cancelled-context text, notification deduplication and observed
timing metadata remain compatible. Duplicate Run IDs retain their original ordinal
and all matching running rows receive terminal updates. Old Turns with no timestamp
remain readable but are excluded from paged history. Ordered starts preserve
execution order on equal/regressing clocks. Indexed tuple cursors preserve exact
nanosecond ordering and Java UTF-16 string comparison, including supplementary
Unicode characters. The full `read`/cancelled-context API still returns its entire
conversation as its existing contract requires; normal paged queries are limited
to 101 rows. Retention is not enabled by this migration.

## Verification

Temporary fixtures cover old-byte preservation, version-1 upgrade, automatic
Spring startup/restart, rollback after partial import, post-import source/backup
mutation, duplicate IDs, Unicode cursors, project isolation, null legacy times,
terminal updates, notification deduplication, concurrent Repository instances,
write failure and a live history Reader during a separate committed write. A child
JVM imports 100,000 Sessions with a 64 MiB maximum heap, then retrieves a bounded
page. Another child is killed after 15,000 rows are imported but before commit:
the prior version remains readable and restart imports exactly once. The final
related run passed 111 tests with no failures/errors/skips. This includes Red/Green
checks that reject negative schema versions and negative hot-journal headers
without changing the original DB/journal or making a backup. The initial PR CI
passed 4,516 tests before these last two guard cases; final full regression and CI
verify the final head. No real user data is used for these checks.
