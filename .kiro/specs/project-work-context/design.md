# Project Work Context: design and verification

## Boundaries

- Project identity: existing ProjectRegistry UUID and canonical existing directory. Session/AgentRunContext owns the target, not a mutable client selection.
- `WorkContextService`: shared application boundary for Shell, Chat Tools and authenticated Web API. No UI rendering or process resumption.
- `WorkContextExtractor` / `LlmWorkContextExtractor`: bounded no-tools MEMORY request, shared strict parser and Draft 2020-12 schema. Historical text is data. Exact source IDs and origin claims are checked before persistence.
- `WorkContextMerger`: pure incremental merge, stable IDs, explicit correction/status/supersession, evidence retention, protected user corrections and observation-time checks.
- `WorkContextRepository`: existing memoryConsolidationDataSource, JSON revisions plus head in one transaction, optimistic revision compare and cancellation rollback. No Sleep watermark or memories writes.
- `WorkContextAutomation`: after durable terminal Turn metadata, bounded FIFO worker. Errors never change the original Run outcome. Optional work-time Git metadata is attached to Turn records.
- `WorkContextAdvisor`: CHAT-only latest-state historical message; ContextAssembler counts and can discard it before dropping conversation.
- `WorkContextPresenter` / formatter: per-client seen keys, short summary and Git conditions. Native uses its Workspace allowlist and a small notice.

## TDD cycles

1. Repository absent → Red; persistence/reopening/history and stale-writer CAS → Green.
2. Merger absent → Red; non-destructive merge, deduplication and user corrections → Green.
3. Output parser absent → Red; shared strict JSON/schema validation → Green.
4. Service absent → Red; Session-owned updates and failure preservation → Green.
5. Formatter absent → Red; budgeted historical context and Git conditions → Green.
6. Controller absent → Red; existing API integration and anonymous-access regression → Green.
7. Native operations / notice absent → Red; existing Workspace adapter and once-per-conversation notice → Green.
8. Candidate certainty absent → Red; INFERENCE proposals and rejection of ungrounded TOOL claims → Green.
9. Protected conflict evidence / interrupted whole-task completion / oversized existing-state projection → behavioral Red, then Green.

Additional checks cover timeout, no-tools extraction, cancellation, simulated DB failure rollback, concurrent sessions, work-time metadata, complete/reopen/withdraw and stale revision conflicts. Fixed-output smoke reopens repositories and creates a new Session before presentation/context injection.

## Deliberate limits

No Auto Sleep, external delegation, cross-project priorities, old-history migration, task replay or process restoration. Input/source/event bounds are documented in the user guide. Schema and exact deduplication do not prove arbitrary LLM semantic claims. Web/Native reuse existing authentication and API/UI operation infrastructure.
