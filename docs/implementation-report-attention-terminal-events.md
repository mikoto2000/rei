# Completion, failure and human-decision attention

Branch: `codex/attention-terminal-events`

Owned `agent.run.completed` and `agent.run.failed` facts now create persistent RUN_COMPLETED / RUN_FAILED inbox items. Normal Run termination is described as termination, not independent proof that a declared Goal was achieved. Failure messages do not copy exception details or model output. Existing long-wait tracking still ends at Run termination.

Dependency terminal facts produce DEPENDENCY_COMPLETED / DEPENDENCY_FAILED items. A USER_ANSWER condition that is ready and waiting produces DECISION_REQUIRED, directing the human to the existing explicit answer control. Event type/state, dependency ID/correlation ID and nonnegative revision are checked. Missing Project/Session and inconsistent facts are ignored. Dependency attention keeps a null public Run ID; its unique source is the dependency ID, without inventing an Agent Run. The existing database schema stores an empty run discriminator for such records; reads expose null. Existing Run rows remain unchanged.

The existing SQLite inbox, Shell renderer and commands, Project-scoped Web API and attention.required Event API are reused. No extra LLM call is made. Acknowledgement neither grants permission nor answers a dependency nor dispatches work. Repeated facts, including after acknowledgement or database reopen, cannot recreate the same item. Completion/failure of every internal Tool or child step is not independently announced, avoiding duplicate notifications for one Run.

TDD: new completion/failure/decision tests failed before implementation (two assertion failures and one missing-item error). Related AttentionService and Web API tests passed after implementation, including restore, identity, redaction, isolation and inconsistent-event rejection.

Native desktop Inbox controls and other audit entries remain open; this report does not claim overall completion.

Validation: full Maven suite passed: 2,921 tests / 558 suites; failures, errors and skipped = 0. git diff --check passed.
