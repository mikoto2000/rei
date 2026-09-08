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
