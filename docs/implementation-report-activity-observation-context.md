# Activity observation context

Status: Implemented (opt-in immutable observation references)

Branch: `codex/activity-observation-context`

Merged into: `main` (commit identities are recorded in Git history)

Implemented:
- Preserve selected Project, current Work Context revision/update time, observation-time Git branch/commit, and bounded Item/TOOL source references in existing Activity JSON.
- Read foreground process, task Item, file, command reference and Session/Run from the same observation; retain original source times and declared certainty/status.
- Default off; reuse bounded read-only Git and existing Activity gates. No additional LLM, screenshot, command execution, context write or raw command/result/work text copy.
- Reject changed owner/root, foreign/future contexts and mismatched source Project; preserve Project evidence on optional capture failure, propagate cancellation.
- Twenty Items/eight sources each, partial on truncation, relative file metadata with secret/outside path exclusion; absolute repository path is not copied.
- Keep legacy constructors/JSON and saved-event joins. SQLite restart preserves references independently of later Work Context history.
- Optional lazy Git/repository dependencies preserve standalone Activity configuration.

Tests:
- Red: missing observation source/type compilation failure confirmed.
- Seven new tests: bounded/private-free capture, default off/owner change/future exclusion, real SQLite restart, source Project mismatch, no IO when disabled/failure/cancellation, old JSON/missing context, property binding.
- Related Activity evidence/storage and Work Context suites passed.
- Full regression initially found mandatory Git dependency in standalone configuration. Fixed to an optional port; targeted configuration regression passed.
- Final full profile: 2,990 tests / 570 suites, zero failures/errors/skips.
- Diff check passed. Merge-time focused regression is run before main Push.

Result: PASS

Remaining:
- Association is selected/saved work context, not proof of user engagement, independent Task execution or task completion.
- Command bodies stay in their existing source system; references do not invent a command.
- Git/OS/DB capture is not an atomic cross-system snapshot. Meaningful task attribution and dedicated UI are additional candidates.
- External Agent fix/continuation, semantic patch review/fix and the remaining audit items are not completed by this change.
