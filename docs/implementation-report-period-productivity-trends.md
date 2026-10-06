# Period productivity criteria and continuity trends

Branch: `codex/period-productivity-trends`

Weekly and monthly Activity reports now include a 0..100 user-criteria index, daily index values and a previous-period point difference. It is the estimated time in the user's chosen categories divided by classified observed estimated time. Unknown classifications are excluded, unobserved time is not scored, and a missing configured criterion or zero classified evidence gives no score. This index is explicitly a proxy for category alignment, not a measurement of outcomes or psychological focus. Existing observation/unknown coverage and partial-period disclosures remain visible.

The index reuses the persisted category criteria from /activity coaching configure. Fresh default settings with revision zero are not treated as a configured score. One settings snapshot is used for both compared periods. Coaching's enabled flag controls advice, not a human-requested calculation; no extra LLM, capture, notification, or advice reservation is performed. SQLite preferences are reloaded after restart. Production ActivityTimeline receives the existing store through configuration; legacy constructors retain an unconfigured index.

Concentration candidates are blocks with at least 25 minutes of estimated observed time in the same recognized Project/category/observation continuity. Blocks split on unknown Project/category, a continuity change, or a gap over 120 seconds. Gaps are never added to their observed duration. Counts, observed candidate minutes and longest stable block are shown with previous-period counts. Interruption candidates are adjacent Project/category switches within the same continuity and at most 120 seconds apart; an unobserved long gap is not reported as an interruption. These remain classification-based estimates.

The same clipped/deduplicated intervals already used for period totals drive the new metrics, preserving overlap, midnight, timezone and calendar-range behavior. Aggregate's original constructor remains compatible for existing coaching callers.

TDD: metrics and criteria constructor failed compilation before implementation. Focused tests passed for unknown exclusion, undefined criteria, day values, a 50/100 previous-period comparison, duplicated observations, stable 30-minute blocks, long gaps, classified switches, changed continuity, invalid criteria and SQLite preference reopen reaching both weekly/monthly formatting with coaching disabled.

Other audit entries remain open; this feature does not claim overall completion.

Validation: full Maven suite passed: 2,934 tests / 559 suites; failures, errors and skipped = 0. git diff --check passed.
