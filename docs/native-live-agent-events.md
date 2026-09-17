# Native live AgentEvent activity

## Investigation (before implementation)

ShellAgentEventRenderer consumes semantic AgentEvents directly. ChatExecutionService,
ToolEventCallbackDecorator, AgentEventChatModel, AgentSkillAdvisor, WorkingSet and
RunExecutionContext already publish the required lifecycle events. No new event type
is needed. Shell rendering strings are not part of the Web API.

| Shell meaning | Actual event types | Correlation |
|---|---|---|
| Run lifecycle | agent.run.started/completed/failed/cancelled | runId |
| Assistant streaming | message.started/delta/completed | messageId |
| Thinking lifecycle | thinking.started/delta/completed | thinkingId |
| Tool execution | tool.started/completed/failed | toolCallId |
| LLM timing | llm.request.started/failed, llm.response.first_token/completed | requestId |
| Skill selection | skill.selection.started/completed/failed | selectionId |
| Skill routing | skill.routing.started/completed/failed, skill.candidates.evaluated | envelope correlationId |
| Progress / stagnation | progress.detected, stagnation.updated/detected/replan_requested/recovered/stopped | runId |
| Working Set changes | working_set.item.added/removed | itemId |
| Working Set search | working_set.search.started/completed | searchId |
| Working Set context | working_set.context.injected | sequence |

Other Shell categories (delegation, subagent, background process, task, topic,
context budgeting, file summaries) are outside this Chat activity slice. Unknown
events still advance the native sequence cursor without creating UI activity.

The current tool summaries are truncated arbitrary input/output. Truncation alone
does not make arguments, command environment or returned file contents safe. The
external mapper must omit these raw summaries and expose names, lifecycle, timing
and aggregate counts instead. Thinking content, search queries, progress evidence
and internal errors likewise need not cross this boundary.

## Boundaries

Shell and Native use the common AgentEvent schema with separate projections.
WebApiEventDto is the external versioned schema. The existing SSE endpoint, global
sequence, Last-Event-ID and bounded ReplayBuffer serve reconnect, not long-term
history. Native activity belongs only to RunManager's in-memory projections.
Session Turn APIs and persistent client metadata continue to contain no activity
trace. Opening historical sessions renders only User / Assistant turns.

## TDD ledger

The work used small Red → Green → Refactor slices, not tests added after a complete implementation.

1. `WebApiEventDtoTest`: raw arguments/summary leaked (Red); removed them (Green);
   extracted WebApiEventMapper and changed SseBridge to use it (Refactor).
2. Aggregate mapping test: stagnation counts absent (Red); explicit per-event
   allowlists for LLM, skill, stagnation and Working Set (Green).
3. Security tests: internal error text/class exposed (Red); generic external
   error, known-key and Bearer/Basic redaction including envelope strings (Green).
4. Native message/tool test: missing message model/timing/error fields (Red);
   message lifecycle and in-place tool updates (Green).
5. Native activity test: missing activities (Red); LLM/progress/Working Set
   reducer (Green), extracted presentation model into `activity.rs` (Refactor).
6. Skill correlation and ownership: no skill rows, wrong session accepted (Red);
   envelope correlation and ownership validation (Green).
7. HTTP submit/reconnect test: RunView omitted messages/activities (Red);
   explicit RunView DTO fields (Green). Replayed duplicate deltas are applied once.
8. React test: Activity disclosure absent (Red); projection-only RunActivity
   component (Green), formatting and responsive scroll area (Refactor).
9. Added lifecycle mapping matrix, live/replay/Shell regressions, browser tests
   and an application reopen test proving runtime activity is not persisted.

Targeted commands used: Maven `-Dtest=WebApiEventDtoTest`, then SSE / Shell
renderer / DefaultAgentUiProjection tests; Cargo `--test projection`,
`--test streaming`, `--test application`; Vitest `Chat.test.tsx`.
Windows Maven uses JDK 25, offline cached repository `.m2/repository`.

## External schema and security

Envelope remains version 1: `id`, global numeric `sequence`, ISO UTC `timestamp`,
`type`, `version`, `sessionId`, `turnId`, `runId`, `projectId`, `correlationId`,
`parentEventId`, `payload`. There is no new endpoint and no new AgentEvent type.

The mapping table above is the public live activity list. Payload fields are
selected per event, so an internal field with a familiar name cannot leak through
another event's schema. Types outside this allowlist carry their envelope with an
empty payload; Native ignores them while advancing the cursor. This tightens the
previous global field-name allowlist, including removal of arbitrary summaries.

- Tool: `toolCallId`, `toolName`; completed adds `duration` (milliseconds),
  `files`, `bytes`, `matches`, `items`. Failed adds external `error`.
  The internal failed payload has no duration, so Native leaves it absent.
- Message: `messageId`, `role` at start/completion, `delta` or completed `text`.
- LLM: `requestId`, `feature` at start, `durationMs` for first token/completion/
  failure. First-token duration is measured from request start.
- Skill: `selectionId` or envelope `correlationId`, selected names, candidate/
  total counts, routing invocation, elapsed/load timing. Candidate scores and
  free-form warnings are omitted.
- Stagnation/progress: `consecutiveNoProgressIterations`, `threshold`,
  `stagnationReplanCount`, `maxStagnationReplans`. Evidence/reasons are omitted.
- Working Set: item identity/kind/path, search identity and aggregate counts,
  before/after sizes, injected item/character counts. No file contents or query.
- Thinking: lifecycle and `thinkingId` only; no reasoning text.

Tool arguments, raw outputs, prompts, arbitrary environment values, internal
exception details, stack traces and error paths have no public field. All errors
are `{ "type": "operation_failed", "message": "Operation failed." }`.
Public strings use CredentialRedactor (credential assignments, private keys,
API-key patterns), exact configured API key replacement and Bearer/Basic masking.
Shell's formatter and shared internal events are unchanged.

## Native projection and UI

`Projection` / `RunView` retain run ownership/status/stream state/cursor and add:

- `messages`: messageId, role, text, completed; deltas append to the same message,
  completion replaces provisional text. `assistantText` derives from assistant
  messages for the existing transcript/history integration.
- `tools`: keyed by toolCallId, preserving start timestamp and summary while
  completion updates status, finish timestamp, duration and sanitized error.
- `activities`: ordered presentation rows (category, label, status, summary,
  timestamps, duration, first-token time, metrics, error). LLM and skill lifecycle
  rows update by stable IDs; progress and Working Set changes retain sequence
  identity. Routing candidates update the routing row rather than creating a
  separate invocation. Working Set removal retains the removed item's name.
- `workingSet`: current items, separate from the live change observations.

The reducer checks version, SSE id/type, run and provided session/turn/project
ownership. Sequence <= the current cursor is ignored. Gaps in global sequence are
normal because other runs share the counter. Heartbeats never change the cursor.
Unknown types advance the cursor without adding activity. Terminal run state is
final; replay-gap recovery still marks the view incomplete instead of inventing
missing observations. Old envelopes without optional ownership/timestamp fields
remain readable; correlation-required events require their real correlation ID.

React only renders these DTOs. The `timeline` presentation list interleaves text
segments and immutable Tool/Activity observations in accepted global sequence
order. Text deltas coalesce only while adjacent to the same message. A tool's
start and completion occupy their actual arrival positions; completing a tool
does not rewrite its earlier start observation. Unknown events and heartbeat
frames add no rows. Replayed duplicate sequence numbers add no rows.

Message completion appends a missing suffix without repeating accumulated text.
If the completed answer revises the streamed text, earlier fragments for that
message are replaced by the authoritative answer at completion time. A stored
final answer remains available after an incomplete replay. Historical Turn-only
rows still show User / Assistant only. Older snapshots without the timeline DTO
retain the previous grouped Activity fallback; newly received runs render inline
on desktop and mobile. Current Working Set remains a separate disclosure.

## Integration coverage

- Server: live run/LLM/tool start → disconnect → interleaved other run → tool
  completion and delta replay → live LLM/message/run completion. Asserts global
  ordering, correlation, sanitized content and terminal stream closure.
- Rust HTTP: POST `/api/v1/chat` → SSE → disconnect after tool start → request
  with Last-Event-ID → duplicate start and delta replay → heartbeat → live
  completion. Final tool, LLM timing, message and cursor are asserted.
- Existing SSE queue overflow, replay gap, cancellation, heartbeat and Shell
  formatting tests are retained.
- UI unit tests cover running/completed/failed tools, LLM timing, stagnation,
  streaming/final text, terminal controls and historical Turn-only rendering.
  Chrome tests run at 1280×900 and 390×844 and verify collapse and overflow.
- Application reopen restores server metadata and no runtime runs/activity.

No SessionTurn schema, ReplayBuffer capacity/retention or persistence model changed.
No live API credentials or external LLM service were used in validation.

## Final validation (2026-09-17)

Server full suite: 1,816 tests, 0 failures, 0 errors, 2 existing skips in
ExternalAgentPolicyTest. Rust: 58 passing tests; frontend: 29; Chrome desktop and
mobile: 8. TypeScript, ESLint, Prettier, cargo fmt and clippy (native/all-targets,
warnings denied) pass. The Windows Tauri debug application with embedded assets
built at `target/native-live-desktop/debug/rei-client.exe`. An already running
client locked the normal output file, so a separate Cargo target directory was
used. No existing client process was stopped.

Android cross-check was attempted and blocked by the absent NDK clang toolchain.
iOS/device checks and manual operation against a production Rei server were not
performed. Browser checks use projection fixtures; Rust integration tests use a
real local HTTP/SSE mock server.

Full-suite validation also exposed pre-existing test races: stdout/stderr were
observed separately, a one-second PowerShell startup deadline was too short, and
random AlphaChars input could be the FTS operator `OR`. Test-only fixes wait for
both output streams, allow ten seconds for auto foreground completion, and quote
literal search tokens. No unrelated Tool/Memory production behavior was changed.

Implementation commits: `ab5d9e4` (server mapper/SSE), `69875dd` (native reducer),
`61da566` (responsive Activity UI), `01cc110` (runtime-only reopen regression).
The remaining regression-suite and documentation commits complete this work;
use `git log main..feature/native-live-agent-events --oneline` for the full list.
