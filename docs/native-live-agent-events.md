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

Red/Green commands and final validation are recorded below as each slice completes.
