# Event and replay cursor storage

Schema 4 adds `agent_events`, `event_sequences`, `event_cursors` and
`event_imports` to root `storage.db`. Each Event is one typed JSON record plus
indexed project, sequence, ID, timestamp, Session and Run fields. Project
sequence allocation and the body/reference metadata commit together; counters
do not depend on retained rows and are never reused. Production Spring beans use
`SqliteProjectAgentEventStore` and `SqliteEventCursorStore` after the startup gate.
Legacy constructors remain available for compatibility fixtures.

The startup transaction strictly streams `projects/<UUID>/events/events.jsonl`.
It preserves IDs, payload subtypes, timestamp nanoseconds and original increasing
sequence, including gaps. Imported rows are checked by count and streamed SHA-256
against source records. Source fingerprints and the verified backup are checked
again before commit. Original JSONL and consolidation databases are retained.
Completed schema-4 startup does not reimport stale legacy files. Root-level or
custom Event files outside the known project layout remain unmanaged sources.

Legacy Scheduler cursors are read from `memory-consolidation.db` without writing
that database during migration. UTF-8 byte boundaries map to Event sequences;
the original offset/discard values and generation remain as migration evidence.
The generation and sequence form the new compare-and-set cursor. Activation
rewinds this cursor before publishing a waiting schedule. Signals precede cursor
advance, retaining at-least-once replay and existing idempotent Schedule
transitions. The two databases do not provide a new exactly-once transaction:
a failed activation may cause additional, harmless replay. Schedule Event
references are protected before publishing the corresponding pointer.

The legacy replay reader discarded lines of 64 KiB or more. Such valid Events
are retained in indexed history, but preserve that replay exclusion. A verified
partial discard cursor inside such a line maps to its preceding sequence. An
ambiguous cursor inside a normal line, discard at a complete boundary, malformed
UTF-8/JSON, duplicate identity/sequence, foreign project, unknown payload, or
incomplete tail stops ordinary startup. No records are silently skipped or
repaired. Restore or explicitly repair a *copy* after inspecting the verified
backup; do not delete source data to bypass a failure.

Migration limits are one million project entries and one million records per
source, with at most 1 MiB per JSONL line. Reads return at most 1,000 history rows
or 128 replay rows, and decode at most 16 MiB per page. These are explicit safety
limits: an oversized source fails closed instead of using unbounded memory.
Event bodies receive `stored_objects` metadata, but remain protected as
`reference-check-incomplete` until a collector proves its reference coverage.
This phase does not physically remove Event rows or old files and leaves all
automatic retention disabled.

Temporary-data tests cover typed round trips, Unicode offsets, generation CAS,
concurrent sequence allocation, malformed inputs, legacy oversized-line replay,
real Spring/Scheduler wiring, duplicate suppression and a 100,000-Event import
in a child JVM with a 64 MiB heap. Existing startup interruption/backup tests also
exercise the complete schema transaction.
