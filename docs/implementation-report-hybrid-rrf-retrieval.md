# Hybrid RAG independent retrieval

Status: Implemented (opt-in independent dense/lexical + RRF)

Branch: `codex/hybrid-rrf-retrieval`

Merged into: `main`

Implemented:
- Independent retrieval port on existing SQLite and lazy vector stores; preserve default legacy behavior.
- Bounded generic RRF with deduplicated identities, stable ties and explicit provenance ranks.
- Opt-in document search pipeline, best fused chunk document score, existing aggregation/snippet/rerank before limit.
- CandidateReranker port around existing generic HTTP service; no duplicate rerank implementation.
- Fix adjacent lexical evidence crossing source when a Doc ID was reused.

Tests:
- Red: absent fusion/pipeline classes compilation failures confirmed.
- Additional Red: source-crossing neighbor fixture failed before adding source guard.
- Nine added tests cover fusion, duplicate bounds, filters/thresholds, cancellation/errors, independent actual SQLite retrieval, source isolation, opt-in/document rerank ordering and legacy default.
- 44 related tests passed (retrieval/vector store/document/rerank).
- Full regression: 2,766 tests / 533 suites, zero failures/errors.
- Initial full regression detected the missing new enabled setting in external config generation; updated the template and verified the final full suite.

Result: PASS

Remaining:
- Skill semantic retrieval, FTS/BM25 or learned sparse retrieval, evaluation/calibration and single snapshot during concurrent ingestion.

Git commit/merge identities are recorded in Git history and the task report.
