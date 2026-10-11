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
2. Added continuation, tools and explicit-model-loop tests before implementation (missing continuation APIs/classes: Red); implemented saved tool history and bounded budget restoration (Green, 30 focused tests).
3. Added early reply, persisted channel Retry-After and cancel-during-send tests (3 failures: Red); added an authenticated bounded early inbox, durable channel limits and terminal-state-preserving TS recording (Green).
4. Added orphaned managed parent test (Red); persisted managed-parent provenance and refuse resuming a lost Goal/Scheduler parent (Green, 22 focused tests).
5. Actual ChatExecutionService + StagnationChatModel + Fake Slack integration passed without polling the model. An accepted-intervention test then failed (Red); save final accepted human guidance with the waiting continuation (Green). Goal/Scheduler callback and partial-timeout tests passed in a 59-test focused run.
6. Spring ApplicationContextRunner validates default-disabled/Shell-disabled tools, YAML record binding and cancellation subscription; identity tests use valid Slack timestamps and follow-up limits are checked independently of the request count.
7. Review found that marking a restored orphaned parent UNKNOWN also requires Run ownership adoption (reproduced as OperationConflict: Red). Adopt only the exact dead waiting owner before stopping, refuse unsent orphan/relocated-Project requests, and mirror terminal external facts even without a resumable parent (Green, 34 tests on origin/main `69bdf49e`). Restart and shared request/Dependency rollback tests also pass.
8. Final review added cancellation immediately after restart and queued-Goal parent registration tests (both Red). RunService now adopts the exact dead WAITING owner for human cancellation, and Goal parent callbacks are attached immediately before actual Chat. An isolated JUnit output directory was used while the broad Maven regression continued, avoiding shared compiler outputs (Green, 33 related tests including Run persistence/cancellation, Goal gateway and actual Chat integration). The final PR head receives the full Windows CI regression.

## Review decisions

- Ordinary Run cancellation uses the regular event subscription (RunService publishes ordinary events). Goal/Scheduler completion callbacks are deferred to the existing Slack pump; they never run under the event-bus cancellation monitor.
- Only WAITING human continuations are eligible after a clean process restart. In-flight admission/LLM execution and orphaned managed parents become UNKNOWN. Saved external results remain readable. No invented parent budget or new Run is used to hide uncertainty.
- Replies never become system messages; restored non-system tool-call/response history is combined with freshly generated host system instructions. Request text, outbound destination and body pass the existing exact-input Permission guard before the shared SQLite transaction.
- The bounded early inbox admits only the configured Bot identity while a matching agent is SENDING; a reply is attached only after exact workspace/channel/root thread TS correlation. Unrelated threads never complete the request. Expired entries are deleted before further early admission.
- No full Session/history is included in the outgoing question. Known credential formats are redacted; this is not general secret classification. The user/admin must choose non-secret explicit questions.
