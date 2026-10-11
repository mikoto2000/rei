# ChatGPT Plan Phase 3 implementation report

Baseline: main after Phase 2 merge 6af8f4a0b873cf94bdd8223d20052f4dddac7cd1 (PR #99). Phase 2 worktree was removed before this branch was created.

Existing local SubAgentRunner already supports bounded repair, tool evidence, semantic judgement, shared Run/Goal reservation and durable consensus. ParallelSubAgentDelegator already owns bounded workers, cancellation and partial results. Phase 3 extends only the OAuth inference adapter and backend dispatch; it does not add another scheduler, planning loop, paid judge or credential store.

New PlanExecutionProperties binds disabled-by-default advanced execution, maximum iterations/repairs/concurrency/bytes/time and optional successful-answer refinement. Effective calls/repairs obey the existing role maxSteps/maxRepairs. Existing wrapper/schema/reference/scope/exact-field completion checks were moved into one verifier and reused for every response. Per-call tokens are recorded before validation, and repair/refinement share one Run, deadline, source snapshot and original approval. Approval digest includes execution settings and role budgets.

PlanClient.Input/inferHistory explicitly serialize user/assistant text into the existing supported Responses body. No server-side conversation/previous_response_id or unsupported token/temperature parameters are added. Invalid prior output is bounded to 16,384 characters. Failed/incomplete/unknown transport, authentication, quotas, cancellation, permissions and exhausted budgets stop execution; no retry/fallback to another billing destination.

PlanReviewSemanticValidator supplements existing structural/source checks with mechanical claim-location, duplicate and severity-contradiction checks. Four support categories describe traceability, never a truth verdict. The original parent remains responsible for adoption. Natural-language reasoning correctness and arbitrary prose completion criteria remain unproven. Roles requiring real Tool evidence or the existing LLM semantic contract are still rejected rather than silently downgraded.

ParallelSubAgentDelegator.Request keeps its old four-field constructor/JSON and adds optional destination fields. Plan children reuse automatic policy, permissions, account-specific catalog and shared reservation. Item retains the full Plan receipt; Batch preserves siblings and adds exact-match comparison only for same-task mixed/model requests. PlanReviewComparison reports common/independent findings, conclusions, citations and unresolved/failed children, always truthVerified=false. It does not infer statistical independence or decide by majority. SubAgentTools propagates the actual ToolContext Run into the existing worker scope across scheduler boundaries.

TDD RED was observed for missing history/receipt/settings APIs, mixed Request fields, refinement configuration and missing explicit Run propagation (one failing context test, eight passing legacy tests). GREEN includes existing backend/client/tool/parallel tests and new repair/history/refinement/deadline/non-retry/semantic/mixed-failure tests. Full regression and GitHub CI totals are recorded in the delivery report/PR after completion. Real OAuth and account inference were not performed.

Main files: PlanClient, PlanConfiguration, PlanExecutionProperties, ChatGptSubAgentBackend, PlanReviewSemanticValidator, PlanReviewComparison, ParallelSubAgentDelegator, SubAgentTools, four existing/new test files, docs/chatgpt-plan-subagents.md. No normal Spring AI model configuration, Codex CLI or Claude Code execution code is changed.

Branch: feature/chatgpt-plan-phase3-advanced-execution.
Worktree: F:/project/rei/.worktrees/chatgpt-plan-phase3.
Official SIWC model/inference, preview and recovery requirements checked 2026-10-11 and preserved. Account/model capability failures use the existing catalog and classified server rejection rather than guessed capability entitlements.
