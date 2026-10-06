# Repository structural summary

Branch: `codex/repository-structural-summary`

Status: Implemented. Merged into `main` after validation; commit identities are recorded in Git history.

The existing READ-only `repositoryMap` Tool now includes a structural `summary` computed from its complete bounded scan, before query and display limits. It reports scanned file counts, parsed/unparsed Java and inventory-only files, module and parsed-package counts, observed Java main entry points with source lines, and import/test-name candidate relation counts. Querying one symbol or displaying one file no longer hides the repository-wide aggregate.

Summary uses the existing Git inventory, Project boundary, secret-path exclusion, AST parsing and content freshness rules. It sends no source bodies to an LLM, executes no compiler processors, and infers no repository purpose or architectural role from names. Test-name links remain candidates rather than coverage evidence. Non-Java files contribute inventory counts only. Default-package Java files are explicitly labelled `(default)`.

Module, package and entry-point output each have a 100-item limit with deterministic ordering. `summary.partial` is true for scan/parse limitations, summary truncation, or a reached symbol cap. Counts describe only the bounded scanned inventory, not every file in an unbounded repository. File-level `partial` and warnings retain their existing meaning, independently of summary truncation. The additive summary is available through existing Tool serialization without a second index or endpoint.

TDD first failed on the absent summary contract. Tests cover aggregates independent of query/display limits, real AST entry points/import/test-name relations, source-body exclusion, freshness after changes/deletion, incomplete parsing and bounded module output. Existing Repository Map and Change/Test Impact tests pass.

Full offline Maven `-Pfull test` passed 2976 tests in 569 suites, with zero failures, errors or skipped tests. Result: PASS. Focused Repository Map and Change/Test Impact tests are repeated after merge before pushing main.

Remaining: multi-language AST and complete semantic dependency/coverage analysis; other incomplete audit requirements remain outstanding.
