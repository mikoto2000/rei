# Activity Work Context correlation

Status: Implemented (owned saved-event references)

Branch: `codex/activity-work-context-links`

Merged into: `main`

Implemented:
- Preserve event/session/turn/run identifiers in bounded existing Activity recent-event evidence.
- Exact project/event/session/run/time join to TOOL evidence in immutable saved Work Context revisions.
- Read-only selected-Project Shell context command, bounded output, Git snapshot timestamp semantics.
- No additional LLM/capture/Git process, context writes, private text or path copying.

Tests:
- Red: absent identifier accessors and correlation service compilation failures confirmed.
- Five tests cover bounded source privacy/expiry, exact references and Project isolation, legacy/mismatched Session, SQLite restart and old JSON, Shell selection/read-only routing.
- Related Activity evidence safety/storage and Shell completion passed.
- Full regression: 2,757 tests / 531 suites, zero failures/errors.
- Initial full regression found a mandatory Work Context dependency in standalone Activity configuration; switched to a lazy optional repository port and verified the final full suite.

Result: PASS

Remaining:
- Foreground/task semantic attribution, observation-time Git/file/command correlation, independent Task ID and dedicated Web/Native UI.

Git commit/merge identities are recorded in Git history and the task report.
