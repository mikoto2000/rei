# RAG and Skill retrieval quality evaluation

`RetrievalQualityEvaluation` consumes bounded human-labelled cases: query, graded
relevant document/Skill IDs, hard negatives, expected top-k and cutoff. It computes
Recall@k, Precision@k (denominator k, including missing slots), MRR@k and graded
nDCG@k (`2^grade - 1`, ideal relevance order). Duplicates cannot add credit. An
expected top-k match is recorded separately from metrics and never proves truth.

`compare` evaluates LEXICAL, DENSE and RRF with rerank off/on, preserving per-case
rankings and macro averages. Provider identity is explicit; `truthVerified=false`.
Limits are 128 cases, 256 candidates/labels, query8192 chars, identity1024 chars,
grades1..3 and a shared 30-second comparison deadline checked between callbacks.
Cancellation and provider failure abort the comparison instead of producing a
successful zero score or publishing partial metrics. Provider calls need their own
bounded transport timeout and authorized model budget.

`HybridRetrievalEvaluationAdapter` calls existing lexical/BM25/dense rank streams,
`HybridRetriever`/RRF, and `CandidateReranker`. `SkillRetrievalEvaluationAdapter` uses
the existing keyword selector and SemanticSkillSearch. Dense-only Skill evaluation
supplies an empty lexical rank stream; existing single-stream fusion preserves its
ordering. Skill instructions are never embedded or sent to the ranker. Evaluation
rejects missing providers, invalid vectors, altered candidate sets and detected model /
rerank fallback. Existing RerankService implements `rerankForEvaluation` to expose
disabled/failed HTTP providers and invalid results. Third-party rankers must follow
this evaluation contract; a provider that hides failures cannot be independently
distinguished from a valid unchanged ordering. Production defaults and fallback
behavior are unchanged.

Anonymous fixtures are in `src/test/resources/evaluation/retrieval-quality.json`.
They include a misleading Artifact UI document and Docker Compose Skill sharing
query terms with the intended result. Embedding/rerank providers in normal tests are
explicit deterministic fixtures, not measurements of an actual model's quality.

```
./mvnw -Pfull -Dtest=RetrievalQualityEvaluationTest,RetrievalQualityPipelineTest test
```

The test harness writes inspectable `target/evaluation/rag-quality.json` and
`skill-quality.json` comparisons. Actual provider evaluation can construct the same
adapters with existing authorized providers and its labelled corpus; no model is
installed, selected or charged automatically. A fixture result does not justify
changing a production model without a relevant labelled corpus evaluation.

No DB migration or runtime feature toggle is needed: the evaluation objects are
explicitly constructed and are not registered as production Tools or background jobs.
