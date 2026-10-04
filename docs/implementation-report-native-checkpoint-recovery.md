# Native Checkpoint recovery

Branch: `codex/native-checkpoint-recovery`

The Native client now lists saved Project checkpoints, explicitly reconciles their state, previews changes, unknown operations and blockers, and offers separate confirmation for resume or abandon. Resume is shown only for authoritative CONTINUE or CONFIRMATION_REQUIRED reconciliation decisions; saved RUNNING alone never establishes live execution. Existing server reconciliation and checkpoint leases remain authoritative immediately before resume.

Rust uses authenticated, typed, Project-scoped endpoints and validates checkpoint and Run ownership. An accepted resume attaches the actual Run to the existing execution list and SSE projection while retaining the saved Session. Explicit existing-Run tracking uses reads, never another resume POST, and reuses an already registered projection. Failed post-acceptance reads do not automatically resend resume. Credentials remain in the Rust vault boundary.

Server/Project changes clear previews and stale responses cannot populate another owner. Synchronous in-flight guards prevent duplicate actions. Mobile scroll clearance keeps explicit confirmation above fixed navigation; desktop/mobile screenshots were inspected.

TDD began with missing Rust operations and screen imports. Tests exercise authenticated endpoints, rejected foreign ownership, post-acceptance mismatched Session without retry, real Native Application registration and read-only tracking, explicit UI confirmation and BLOCKED reconciliation. Browser tests use the shared App fixture rather than claiming packaged desktop or external-server execution.

Validation: React 56 tests in 17 files; full Rust tests and Native feature check; TypeScript, ESLint, Prettier and production build passed. The initial browser run passed 17/18 and exposed mobile button occlusion; the layout fix passed both recovery browser tests. The full browser rerun passed all 18 desktop/mobile tests. Java sources are unchanged. Goal/Scheduler recovery and dependency answer controls remain outstanding, as do other audit requirements.

