# Multi-file Document Change Set

Existing `TextChangeSetRepository` owns the additive SQLite table
`text_document_change_sets`. Existing single-file proposals and APIs remain usable.
`TextChangeSetService` delegates multi-file operations to
`TextDocumentChangeSetService`; Tools use captured Project/root/session and the
existing permission policy and exclusive conversation queue.

## Proposal and application

`proposeTextDocumentChangeSet({operations:[...]})` accepts UPDATE, CREATE, DELETE,
and RENAME. Each operation names `path`, optional rename `target`, exact existing
`expectedText`, and optional `replacement`. CREATE requires absence. DELETE has
no replacement. RENAME requires an absent destination and can preserve its text.
Parents must already exist. Inspection returns baseline/proposal/current hashes,
existence and availability separately, a redacted diff, and whole-proposal SHA.

`inspectTextDocumentChangeSet(id)` is READ. Proposal and
`discardTextDocumentChangeSet(id, proposalSha256)` are LOCAL_WRITE. Apply is
LOCAL_WRITE and DESTRUCTIVE, requires EXCLUSIVE ownership and the exact reviewed
whole-proposal SHA. READ_ONLY and child sessions cannot write. No new toggle or
automatic editing path is introduced; existing permission configuration applies.

Limits: 32 operations, 64 distinct case-insensitive paths, 64 KiB UTF-8 per file,
256 KiB combined before/after text, 1 MiB saved payload. Binary, malformed Unicode,
traversal, sensitive configuration, symlinks and Windows junctions are rejected.
Staging paths are checked before saving. No directory creation or overwrite rename.
100 safely terminal records per Project are retained; active proposals and uncertain
operations are never pruned. Total capacity is 128 per Project and 1024 globally.

Apply claims durably before filesystem writes, stages both replacement and baseline
backup files with forced file writes and preserved POSIX permissions or Windows ACL,
rechecks all baselines, publishes each path, then verifies the entire set. Only after
the APPLIED receipt is saved are owned staging files cleaned and file events emitted.
Historical APPLIED reads never replay writes or events; `currentMatches` distinguishes
current content from a historical successful application.

## Failure and recovery

Ordinary failure and cancellation attempt reverse rollback. Content matching neither
saved version is preserved. Partial or uncertain recovery remains UNKNOWN. A process
identity/start-time claim and journal survive restart; lost operations become UNKNOWN
on inspection without automatic replay or rollback. UNKNOWN blocks another batch and
the existing single-file Apply on the same root. Fatal-stop fixtures cover this path.

Human recovery goes through the existing exclusive conversation queue:

```
/document show UUID
/document rollback UUID proposalSha256
/document clean UUID proposalSha256
```

Recovery Tools verify the actual captured human request, not a model approval flag.
Rollback accepts UNKNOWN only and permits at most three explicit attempts. Cleanup
accepts safely terminal records and deletes only saved owned staging paths whose
current content hash matches the journal. Inspection exposes staging hashes and paths
for manual diagnosis. External edits are not overwritten. Filesystem operations are
bounded by file and text counts; a blocked OS call has no hard real-time guarantee.

The filesystem does not provide atomic visibility across several paths. This is a
logical all-or-rollback transaction with durable receipts and explicit uncertainty,
not a promise of physical multi-file atomicity or protection from arbitrary external
writers between filesystem checks. The queue serializes the application's writers.

## External Agent reuse

`ImplementationProposal` uses the same bounded `TextDocumentTransaction` inside its
private worktree, retaining parent-selected manifest and snapshot hashes, text limits,
and forbidden repository configuration checks. A normal second-publication failure
restores earlier writes. Existing durable implementation receipt, independent tests,
and explicit reviewed merge remain required. The adapter's staging journal is in
memory; crash recovery of a private worktree follows the existing implementation
receipt and Git baseline rather than claiming a second persistent document journal.

Tests cover mixed operations, persistence, exact SHA/owner/mode, stale baselines,
duplicate Apply, second-write rollback, cancellation, external edits, fatal stop and
explicit restart recovery, single-file exclusion, bounded discard history, actual
Windows junction rejection, and Tool events emitted once after commit.
