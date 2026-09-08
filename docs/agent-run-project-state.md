# Agent runs and project state: implementation journal

## Investigation (before changes)

- `ReiApplication` reads with JLine, submits commands to a single executor, then blocks in `EscCancellationMonitor.await`. There is no concurrent prompt reader during execution.
- `ChatCommand` delegates to `ChatExecutionService` and narrates the result. The service creates run IDs, owns streaming subscriptions, output-limit replanning, completion events and conversation logs.
- `StagnationChatModel` already owns the explicit LLM/tool-result loop. Tools execute on Reactor boundedElastic. `RunExecutionContext` crosses this boundary in ToolContext; it owns budgets and stagnation tracking.
- Cancellation uses one `CommandCancellationService`, combining execution-thread interruption and Reactor `Disposable.dispose`. ESC previously consumed terminal input during command execution.
- `ConversationIds.chat()` always returns `chat:main`. PromptChatMemoryAdvisor owns memory updates. `ConversationLogStore` separately writes JSONL; history search combines logs and JDBC queries.
- `ProjectService` maintains a mutable atomic current path and a line-based registry. `/project cd` requires prior registration and changes only that path.
- `Tools.currentWorkingDirectory()` reads that mutable project while executing. This must use a run snapshot. Inline attachment paths also need an explicit project base.
- `WorkingSet` is an in-memory LinkedHashMap shared by the application. ActionPlan, task state, checkpoints and file summaries also hold project-related in-memory state and require consideration when switching projects.
- `AgentEvent` is a typed immutable envelope. `InMemoryAgentEventBus` assigns process-wide sequences and isolates listener exceptions. Many events have no run ID. `DefaultAgentUiProjection` tracks run/messages/tools and rejects old sequences.
- `ShellAgentEventRenderer` already provides a separate projection over the same event model, using JLine output. `ShellEventSession` owns its subscription.
- `ReiPaths` stores files under the startup directory's `.rei`. Resource defaults also reference `.rei` for logs, images, skills, MCP, Google tokens and sqlite-vec cache. System logging defaults to `.rei/rei.log`.
- Executors include the Shell command executor, Reactor boundedElastic, background-process workers, asynchronous embedding and scheduler services. A bare ThreadLocal cannot propagate ownership across Reactor boundaries.

## Phase 1 TDD journal

1. Added mailbox FIFO and barrier-based close/enqueue race tests; compilation failed because UserInterventionQueue did not exist. Added the minimal mailbox and verified the tests.
2. Added router tests for idle dispatch, running guidance, and input arriving after mailbox closure. Verified RED, then added the router.
3. Added a controlled tool callback that queues guidance and asserts history remains untouched until tool completion. Added the safe point in the existing loop.
4. Added a real ChatClient test for late guidance, another request in the same run, and user-message persistence. Added the execution-service continuation/close handshake.
5. Added a picocli test showing two Shell submissions return before agent work executes. Connected the asynchronous router without changing synchronous compatibility constructors.
6. Added lexical run-context binding and cleanup tests. Tool callbacks explicitly bind the captured context around existing synchronous services.

Build logs are retained in `target/phase1-*.log`. Maven requires the existing local repository override in this environment:

```powershell
.\mvnw.cmd -o '-Dmaven.repo.local=C:\Users\mikoto\.m2\repository' test -q
```

An initial baseline attempt overlapped test compilation and produced missing generated test classes. That run is invalid as regression evidence; subsequent builds are serialized.

Phase 1 passed the full suite after replacing an existing cancellation-test startup race with a latch. Offline networking initially prevented sqlite-vec integration tests from fetching their extension; the full passing run used network access. Commit: `34cb685 feat: allow user intervention during agent runs`.

## Phase 2 storage and compatibility

- Windows default: `%LOCALAPPDATA%\Rei` (on this host, `C:\Users\mikoto\AppData\Local\Rei`). `REI_DATA_DIR` overrides it; `-Drei.data-dir=...` supports an explicit Java/test override. Linux uses `$XDG_DATA_HOME/rei` or `~/.local/share/rei`; macOS uses `~/Library/Application Support/Rei`.
- `projects.json` stores a `projects` array of UUID, display name and canonical path. Registration persists before switching. Explicit registry relocation preserves identity; automatic move detection is not implemented.
- `projects/<uuid>/conversations` contains conversation JSONL. Chat memory uses `project:<uuid>:chat:main`; the logical `chat:main` API remains available.
- `projects/<uuid>/working-set/files.json` is the Working Set snapshot. `state/last-run.json` is an independent last-run snapshot. `logs/activity.jsonl` contains structured profile activity; system logging lives under global `logs/rei.log`.
- Existing state-service APIs use a Spring project scope. Run Tool callbacks and Advisor before/after callbacks explicitly enter the captured scope. ActionPlan, TaskState, checkpoints, file summaries, recent changes and search caches therefore have separate in-process instances. Working Set and last-run state additionally survive restart.
- Agent execution remains serialized across projects in v1. Each project has at most one accepted active/queued run; further input joins its mailbox. Switching projects does not change a running request's paths, history or log ownership.
- `/cancel` is scoped to the selected project; it does not interrupt an agent belonging to another project.

### Legacy `.rei`

No old file is deleted or rewritten. Automatic import of unnamespaced conversations is intentionally not performed because the owning project cannot reliably be inferred. The new application does not automatically read old `.rei/application.yaml`; when adopting an existing configuration, copy it to the new global `application.yaml` and replace any `.rei` paths with the chosen data directory. Skill definitions and MCP/Google configuration can likewise be copied explicitly. Keep credentials local.

Keep old `memory.db`, `conversation-logs`, profile logs and other databases as a backup. Copying the old database wholesale into a new installation does **not** attach `chat:main` rows to a ProjectId. A future importer must ask for the owning project, namespace those rows, and avoid collisions; this release does not claim to migrate these rows. Old events were not persisted as typed AgentEvent records and cannot be reconstructed losslessly from notification strings.

The registry API supports explicit relocation. Path-management compatibility constructors remain for embedded callers; the runtime default constructor enables project scoping.

## Phase 3 and final verification

Typed AgentEvent JSONL is stored at `projects/<uuid>/events/events.jsonl`, with persistent per-project sequence numbers. The existing bus retains its process sequence API. Queue-based delivery orders concurrent and reentrant publication. Shell restoration uses the existing renderer's compact mode, a default of 20 recent events (`rei.events.recent-limit`), and independent Conversation/Working Set/last-run snapshots. Forward replay pagination remains a separate API.

Tests cover typed round trips, independent project sequences across reopening, malformed tail recovery, listener failure isolation, concurrent/reentrant order, project-switch restoration, hidden-project rendering state, bounded streaming output during input, and a real ChatClient with a controllable tool finishing in A after the Shell switches to B.

Final regression result: **1,380 tests, 0 failures, 0 errors, 0 skipped; BUILD SUCCESS**. See `target/final-all-tests.log`. `git diff --check` also passed. Full Japanese completion report: `docs/implementation-report-agent-projects.md`.
