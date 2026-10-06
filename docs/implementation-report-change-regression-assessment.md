# Change impact regression assessment

Branch: `codex/change-regression-assessment`

Status: Implemented. Merged into `main` after validation; commit identities are recorded in Git history.

The existing READ-only `changeTestImpact` result now includes `regressionAssessment`: affected modules from the full structural traversal, integration test candidates in those modules, and explicit reasons for broader regression. Ordinary candidate display limits do not shrink module discovery or integration suggestions.

Cross-module graph references, build configuration/workflow changes, unindexed changes and incomplete index/candidate output require `BROAD_REGRESSION_REQUIRED`. Build configuration and unknown/deleted/excluded changes conservatively expand discovery to all indexed modules. Expanding scope this way is not reported as evidence of cross-module references. A result without those signals remains `STRUCTURAL_EVIDENCE_ONLY`, never a low-risk, coverage-complete or tests-may-be-skipped verdict.

Integration candidates use observable conventions: integration/it directories, `*IT.java` or `*IntegrationTest.java` names, or SpringBootTest/Testcontainers imports in test files. Each includes its path, module and exact heuristic reason. These are suggestions, not verified annotations, executed tests, actual coverage or semantic dependency claims. Java files in it/integration test directories are also recognised as test candidates by the existing structural traversal.

Module and integration outputs are independently bounded to 100 and sorted deterministically. Truncation marks both assessment and overall result partial and requests broader regression. Existing Project-relative path validation, bounded fresh Repository Map, reverse-import graph, cycle termination and cancellation remain in place. No command, compilation or test execution is added to this analysis Tool.

TDD first failed on the absent assessment contract. Tests cover cross-module references, integration candidates outside the import graph, display-limit independence, build/unknown-change broadening without fabricated graph evidence, it-directory recognition, and explicit assessment output bounds. Existing Repository Map and Change/Test Impact tests pass.

Full offline Maven `-Pfull test` passed 2979 tests in 569 suites with zero failures, errors or skipped tests. Result: PASS. Repository Map and Change/Test Impact tests are repeated after merge before pushing main.

Remaining: actual coverage and complete semantic dependency analysis; other incomplete original audit requirements remain outstanding.
