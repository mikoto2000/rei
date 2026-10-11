# Slack Agent Collaboration Phase 2 investigation

Base: origin/main `25bfeb7f` (2026-10-11). The existing user's CLI worktree has uncommitted work and schema 6; it is not edited or merged into this task.

- Phase 1 owns one SDK Socket Mode connection per data directory. `SlackEnvelopeReceiver` ACKs only after durable admission; `SlackAdmission` excludes every Bot. Its human inbox/session/outbox remains separate.
- `SlackRuntime` invokes the existing ProjectRunQueue/ConversationInputRouter/RunService. Final replies come from durable completed Turns. This remains the only human Slack execution path.
- Tool discovery: `LlmChatClientProvider`, Spring AI `@Tool`, existing `StagnationChatModel` explicit loop. Policy checks take place before the callback. External sends use NETWORK_WRITE plus EXTERNAL_SIDE_EFFECT and retain exact-input approval handling.
- Existing `DependencyAwaiter` polls synchronously for at most 60 seconds. A 300-second Bot wait must instead save the completed tool batch at the existing loop boundary, return the executor, and admit one continuation through the existing project queue. A saved WAITING Run is not a completed Run; the same ID, Session, original request, model counters and tool results are retained.
- Existing dependencies persist state and publish factual events; external requests become EXTERNAL_AGENT dependencies. Existing Scheduler's event continuations remain available; no second polling executor is created. Slack's existing one-second pump drives deadlines and ready continuations, with a durable claim before admission.
- Existing checkpoints/raw tool-result evidence and Run/Goal budgets remain authoritative. No automatic replay of unknown tool operations or sends. Lost continuation admission becomes UNKNOWN and requires inspection.
- Bluesky's feature-specific direct response scheduler has no Session/Run waiting contract. Its sender filtering and claim-before-send conservatism inform the boundary, but no Bluesky source is changed.

## Actual communication prerequisite

Before implementation the task process was checked for token availability (booleans only): SLACK_BOT_TOKEN=false, SLACK_APP_TOKEN=false. No real message was sent. Previous Phase 1 human Slack success does not establish that tenten accepts Bot mentions, its bot_id/user mapping, or a final-response format. Those remain unverified; use SDK fakes and an administrator-configured final prefix only when tenten's behavior is independently verified. Without a prefix collect replies until deadline, return partial observations with TIMED_OUT, never infer completion from silence or an LLM.

## TDD log

1. Repository/identity/state tests added before implementation; Maven test compilation failed for absent SlackAgentRepository/SlackAgentProperties (Red).
