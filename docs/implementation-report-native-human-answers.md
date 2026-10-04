# Native human answer controls

Branch: `codex/native-human-answers`

The Native recovery page now includes saved USER_ANSWER questions, Session/dependency identity, state, reason, deadline, prerequisites and saved reply. A nonblank answer opens a separate confirmation showing the exact question, Session and version before submission. JavaScript only permits safe integer versions and the existing 4096 UTF-16-unit answer limit. Terminal dependencies have no answer form.

Rust extends the existing explicit workspace allowlist with Project-scoped dependency reads and versioned answers. Typed DTOs require exact Project, nonempty identity, known state and bounded lists/prerequisites. Mutation receipts require the requested ID, USER_ANSWER kind, the next version and a nonblank saved answer. The React boundary additionally checks the original Session. Existing authenticated transport requires HTTP 200 JSON, keeps credentials in Rust and does not automatically retry a POST.

Server/Project changes clear drafts and confirmations. Generation guards ignore delayed reads/results, and a synchronous busy guard prevents duplicate clicks. An answer failure closes confirmation and invalidates the old question list; explicit refresh and review are required before another submission. The returned saved reply is displayed without launching another Run, granting Tool permission or claiming dependency completion. The existing server watcher and prerequisite semantics remain authoritative. Older-server endpoint failure is contained within the question panel so Checkpoint recovery remains usable.

TDD began with missing Rust operation variants and missing React component. Rust tests use authenticated local HTTP to exercise exact versioned bodies and reject foreign/unconfirmed responses without retry. React tests cover review-before-send, safe owner changes, delayed old-owner results and failure without retry. Shared-App browser tests cover desktop/mobile confirmation, saved reply and absence of a new checkpoint Run; screenshots were inspected on both viewports.

Validation: all 59 React tests in 18 files and all 20 browser E2E tests passed. Full offline Rust tests, Native feature check, TypeScript, ESLint, Prettier, production build and git diff --check passed. Java sources are unchanged in this branch. Goal/Scheduler recovery controls and the other audit requirements remain unfinished.
