# Durable SubAgent checkpoints

Set `rei.subagents.durable-enabled=true` / `REI_SUBAGENTS_DURABLE_ENABLED=true` deliberately (default false).
The existing `delegateTask` and bounded `delegateTasks` runner then persist children belonging to a human Project/root/session.
They require the parent's existing shared Run/Goal model reservation; standalone executions remain subject to the existing standalone budget policy.
No separate task executor, model provider, unrestricted Tool catalog or automatic startup scheduler is introduced.

`rei.subagents.durable-max-total-tokens` / `REI_SUBAGENTS_DURABLE_MAX_TOTAL_TOKENS` is an optional additional child token cap (default 0).
The parent budget always applies. Each child also retains its original maximum model calls, limited by the definition and available parent calls at creation.
Every attempt reserves a durable call before the parent reservation and native model invocation. Failed parent reservations are conservatively consumed;
resume never replenishes child calls. Usage reports update durable token consumption before forwarding to the parent budget.
If token limits apply, an unreported model call on cancellation/process loss marks usage unknown and blocks further model calls.

The application creates `subagent_checkpoints` in the existing primary SQLite data source. A versioned, bounded checkpoint records:
stable child task ID, original parent Run, Project/canonical root/human Session, selected agent/task/context, current child Run,
last state/revision, call reservations/consumption, token usage uncertainty, Tool argument hashes/statuses, final result/hash and process owner identity.
Recognized credentials are redacted in retained task/context/result and reconciliation notes. Source bodies, raw intermediate Tool output and reasoning traces are not checkpointed.
Result storage is capped at 64 KiB characters and the serialized record at 256 KiB bytes; at most 64 Tool operations, 1024 children and 8 MiB total snapshots are retained.
Admission and growing results both enforce the total cap. Capacity/revision failures preserve the previous checkpoint and do not authorize a Tool invocation.
Operator cleanup is deliberate; unknown histories are not automatically evicted to make room.

Each execution still receives an ordinary `subagentRunId`; results additionally expose `durableTaskId`.
When Task Manager tracking is enabled, its existing child Run retains the stable checkpoint reference, parent link and original human Session.
UNKNOWN Tool outcomes produce UNKNOWN in the child checkpoint and existing Run registry. The Task projection includes a `SUBAGENT_CHECKPOINT` reference.
Enable the existing HTTP/Task Manager settings to display those Run projections in Native.

The parent Chat Tool catalog exposes `listDurableSubAgents(offset, limit)` and `getDurableSubAgent(childId)`.
Listing/reading is scoped to the current human Project/root/session, supports bounded pagination, and never invokes a model or retries work.
In an ordinary current user message, supply the exact text:

```text
subagent resume <childId> <revision>
subagent reconcile <childId> <revision> <operationId> SUCCEEDED
subagent reconcile <childId> <revision> <operationId> FAILED
```

The assistant calls `resumeSubAgent` or `reconcileSubAgent` with the saved IDs/revision and the shared current Run context.
Tool arguments or saved context cannot authorize these actions. Resume requires an exclusive human Run and rechecks the definition/schema and observed Git baseline.
Git observations do not prove every source file unchanged; the resumed child must re-read source and revalidate requirements.
Reconciliation is item-specific human confirmation, with an accompanying observation note; it executes nothing.
Unknown token usage cannot be erased by confirming a Tool outcome.

Resume starts a fresh bounded child execution of the saved task with checkpoint observations and the remaining durable budget.
It does not replay the old model transcript, intermediate Tool output, inherited approvals or reasoning trace.
Completed children cannot resume. STARTED/UNKNOWN Tools must first receive explicit outcome reconciliation; succeeded operations are supplied as observations, never as new instructions.
The existing audited child Tool policy remains read only, rejects recursive delegation, and keeps independent structural/evidence/optional semantic validation.
Automatic transient Tool retries are disabled for durable children so a failed attempt remains available for explicit inspection.

On startup, a RUNNING child whose PID/start identity no longer exists becomes UNKNOWN, including pending Tool operations.
Live owners are not stolen. Atomic SQLite updates and optimistic revisions prevent two instances from claiming the same child;
neither restoration nor reading starts a model. Hard kill recovery relies on the saved boundaries rather than termination hooks.
Tests use deterministic model streams, local SQLite, recreated runners and the existing Run/Task projection; no paid model is run.
