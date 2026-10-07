# Final crash consistency boundaries

The existing durable Run, child/DAG, scheduler, Artifact, multi-file transaction,
external implementation and provider outbox structures remain the foundation.
This phase closes five concrete gaps found in the final audit.

* Notification completion compares attempt generation, provider and destination.
  A late receipt from before UNKNOWN/manual retry cannot complete the new attempt.
  Actual dispatch owns an active lease; persisted PID/start/heartbeat and startup
  reconciliation preserve live operations and mark dead/lost sends UNKNOWN. Unknown
  sends require explicit duplicate-risk acknowledgement, and are never auto-replayed.
* Patch requirement reviews and diagnosed repairs add nullable PID/start/heartbeat
  columns to their existing tables. Shared narrow process-lease support preserves
  active operations, recognizes PID reuse using process start time, and marks lost
  STARTED rows UNKNOWN on startup or explicit inspection. Older STARTED rows with no
  lease metadata become UNKNOWN. Fatal errors release in-process ownership; a hard
  kill loses that ownership and is reconciled from persisted process identity.
* Review READ tools inspect by ID or list up to128 current Project/root/session
  receipts. STARTED/UNKNOWN produce execution metadata without success proof or SHA.
  Existing exact ID/SHA get and GoalCompletionGate still validate terminal payloads.
* Ordinary single-file history pruning retains guard-linked diagnosed receipts.
  The existing128 per-project capacity remains explicit rather than silently evicting
  a repair's audit evidence. Pending/uncertain receipts already remain retained.

Heartbeat is saved at claim and execution boundaries. It does not override a live
process solely because a timestamp is old. Processes/operations retain their existing
bounded transport/test/model deadlines; underlying OS I/O is not universally interruptible.
No shutdown hook, physical atomicity of multi-path writes, arbitrary external writers,
or complete prevention of uncertain external effects is claimed. UNKNOWN is inspectable
and blocks replay; manual reconciliation is required where an effect may have happened.

DB migrations are additive. No new runtime enable flag is required. READ inspection
may reconcile execution metadata but does not execute commands or write project sources.
Normal regression includes boundary and recovery fixtures; real credentials remain OFF.

Single-file Apply uses the same persisted process lease. Lost APPLYING becomes
UNKNOWN and retains current bytes, including partially written content. UNKNOWN also
blocks a new multi-file transaction in the same root. No automatic rollback occurs.
`/document reconcile-single ID proposalSha256` requires the exact captured exclusive
human Shell request. It only checks that current bytes match the saved baseline or
proposal, then records RECONCILED without writing the source. A different current
version must be inspected and manually repaired first. RECONCILED is historical
metadata, never APPLIED, a test result or a completion proof; Apply cannot replay it.
Guard-linked history remains retained. Read-only/subagent requests cannot reconcile.

Actual child-JVM fixtures claim Review, diagnosed Repair, notification and single-file
Apply, preserve their live states from another instance, then forcibly kill the owned
child. Inspection returns UNKNOWN, no commands/sends/writes are replayed, and partial
single-file bytes remain intact until an explicit fixture human repair/reconciliation.
Ready markers are published with an atomic move and every child is reaped in finally.
A pending single-file Apply also refuses another active/uncertain single-file receipt
in the same root. Exact metadata reconciliation is required before a new write.
The in-process lease key includes the DB identity to separate independent stores.
