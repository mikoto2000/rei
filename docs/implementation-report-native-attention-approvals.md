# Native Inbox and explicit one-time approval

Branch: `codex/native-attention-approvals`

Tauri's existing authenticated workspace-operation bridge now exposes an explicit allowlist of Project-scoped Inbox reads/acknowledgements and Tool approval reads/decisions. It never accepts an endpoint URL or arbitrary body. Rust decodes typed server DTOs, requires exact Project identity and stable mutation IDs, bounds lists at 256, and verifies the acknowledged/approved/denied receipt state. Missing authentication, invalid path segments, foreign Project rows and unconfirmed acknowledgements fail through existing safe errors. No credential is passed to React.

A new Inbox・承認 page is reachable in the Native client's main navigation. It selects a server-owned Project, displays notification messages and source Session/Run/reference, and acknowledges only an explicit notification button. Approval cards show Tool, redacted argument preview, Session/Run, expiry and state. Approval first opens a review card, then a separate one-time confirmation submits true; explicit refusal submits false. Acknowledgement and approval do not start a Tool, submit a chat, answer a human question or resume a checkpoint. Existing server permission and atomic single-consumption rules remain authoritative.

Server/Project/vault changes clear pending previews and old rows; generation checks prevent delayed reads or mutation results from crossing ownership. A synchronous in-flight guard prevents duplicate actions. Foreign Project rows are also excluded at the React boundary. Partial endpoint failure shows a safe error while preserving the other available list, supporting older servers. Refresh is manual; this page does not add background polling or a new notification delivery service.

The page reuses existing responsive page/card layout, wraps long previews and verifies that the mobile confirmation button stays above fixed navigation. Screenshots were inspected for desktop and mobile. E2E concurrency is explicitly capped at two workers: the prior default eight-worker run timed out on initial page loading across both old and new tests and then stalled in teardown; that test run was explicitly interrupted before the bounded rerun.

TDD: missing screen import and Rust operation variants failed before implementation. React tests cover read-only opening, explicit acknowledgement/approval/refusal, ownership, late results and absence of execution submissions. Rust local HTTP tests exercise authenticated Project endpoints, exact false body, DTO ownership and receipt validation. Browser tests use the shared App fixture; they validate desktop/mobile review/confirm flows, rather than claiming a live external-server or packaged Tauri launch.

Other audit entries, checkpoint/Goal/Scheduler recovery screens and dependency-answer controls remain open. This feature does not claim overall completion.

Validation: React 54 tests / 16 files passed; browser E2E 16 desktop/mobile tests passed; full cargo test --offline and cargo check --offline --features native passed. TypeScript, ESLint, Prettier, Vite production build and git diff --check passed. Java server sources were unchanged by this branch.
