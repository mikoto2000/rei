# SubAgent required call contracts

Branch: `codex/subagent-required-calls`

Status: Implemented. Merged into `main` after validation; commit identities are recorded in Git history.

SubAgent definitions may now specify up to 16 `requiredToolCalls`, each containing a Tool name and exact JSON `arguments` object. Required calls must use configured `evidenceTools`, which remain a bounded subset of requested/allowed Tools. Existing constructor signatures and YAML definitions default to no exact-call requirements.

The runner captures parsed actual Tool inputs independently of model claims. After envelope/schema and receipt identity/hash/quote checks, SUCCESS requires each exact-call contract to match an actually cited observation. Calling the same Tool on another file or query, or merely performing the correct call without citing its receipt, cannot satisfy the contract. Object key order and whitespace do not matter; fields, values and array order must match. Missing fields, explicit null and additional arguments remain distinct. PARTIAL/FAILURE may acknowledge missing work but cannot forge evidence.

Configuration accepts JSON-compatible YAML values only, at most 4096 JSON characters per contract. Actual input parsing is bounded to 16384 characters and uses existing strict JSON parsing, including duplicate-key, trailing-value and nesting checks. Invalid input cannot satisfy a contract. Receipts keep their existing four-field citation format, raw-input hash and bounded output. Diagnostics identify only the requirement index and never include configured arguments or observed Tool output.

TDD first failed on the missing contract type/validation method. Ledger tests prove exact argument matching, whitespace/key-order equivalence, uncited/wrong calls failing SUCCESS, honest PARTIAL and rejection of invalid, oversized, duplicate-key or trailing JSON configuration. YAML tests cover optional defaults, exact loading, unsupported Tool requirements and non-JSON values. Real Runner tests verify matching and mismatching calls through configuration, Tool execution, receipt capture and final validation. Existing bounded repair, shared budgets, cancellation and legacy behavior remain in place. Validation adds no LLM calls or automatic execution.

Remaining: free-prose semantic contradictions and independent assessment of Tool output truthfulness; other incomplete original audit requirements remain outstanding.

Full offline Maven `-Pfull test` passed 2983 tests in 569 suites with zero failures, errors or skipped tests. Result: PASS. Evidence/configuration/Runner tests are repeated after merge before pushing main.
