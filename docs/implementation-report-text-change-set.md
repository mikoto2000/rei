# Text Change Set foundation

Status: Implemented (single existing UTF-8 text file)

Branch: `codex/text-change-set`

Merged into: `main` (commit identities are recorded in Git history)

Implemented:
- Exact raw baseline read, proposal/diff persistence, read-only inspection, reviewed hash Apply and unclaimed discard.
- Project/root isolation, strict Unicode/UTF-8 and 64KiB limits, existing regular file and sensitive/outside/linked path rejection.
- Durable atomic application claim, post-write content hash confirmation, stale rejection, receipt-only repeated Apply, no replay of saved APPLYING/FAILED_UNCERTAIN.
- Missing/invalid current file is explicitly unavailable rather than successful or empty.
- Existing editing Working Set/Recent Changes/cache invalidation/FileModified boundary and Policy classifications reused. No new LLM/External Agent invocation.
- Per-Project bounded retained proposals; pending/uncertain claims are not silently discarded.

Tests:
- Red: absent repository/service, missing Tool routes and later discard/raw baseline APIs confirmed before implementation.
- Seven real SQLite/filesystem tests: preview and restart, exact proposal Apply, stale/foreign owner rejection, partial write/no replay, bounds/sensitive/binary/outside paths, saved applying/discard/missing file, competing service/root changes, BOM/CRLF/no final newline.
- Tool integration verifies source-to-proposal-to-Apply flow, FileModified event, registered callbacks, READ/LOCAL_WRITE and unchanged SubAgent restrictions.
- Existing editing/static self-review/Policy regressions passed.
- Full profile: 2,998 tests / 572 suites, zero failures/errors/skips.
- Diff check passed; merge-time focused regression runs before main Push.

Result: PASS

Remaining:
- Multiple file transaction/new file/delete/binary/CP932 and dedicated approval UI are outside this slice.
- Filesystem and SQLite do not form an atomic transaction; interrupted operations remain uncertain.
- Natural-language diagram editing, Document Agent, semantic patch review/fix, external continuation/fix and other audit items remain incomplete.
