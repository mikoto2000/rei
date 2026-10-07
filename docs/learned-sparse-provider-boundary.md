# Learned Sparse provider boundary

The repository's production code, configuration, resources and Maven dependencies
have no configured learned sparse encoder/index provider. Existing SQLite BM25/FTS5,
dense retrieval and RRF remain unchanged. This phase implements the requested optional
boundary without requiring a model, library installation or paid endpoint.

`SparseEncoder` exposes a versioned `modelId` and `encode(text, checkActive)`.
Its immutable Vector contains numeric vocabulary IDs, dimensions and positive finite
weights. Limits: model identity128 characters, dimensions1..1048576, at most4096
nonzero entries, valid in-range IDs, weight<=1000000. Numeric learned vocabulary IDs
are not treated as BM25 term weights.

`SparseRetrieval` is explicitly constructed with the existing `RetrievalCandidates`
BM25 backend and optional encoder/index. `Index` declares matching model/dimensions
and filter capability; unsupported filters use the original filtered BM25 request.
Provider implementations must apply all filters and thresholds before top-k. Results
are bounded, unique, and include LEARNED_SPARSE or BM25 mode and a nonsecret reason.
No model is automatically discovered or invoked. No provider is registered as a Spring
bean or made a mandatory dependency. Default production search is unchanged.

Absent/incompatible/empty/unavailable providers return explicit BM25 fallback.
Cancel and existing Run/Goal model-budget stop signals propagate without fallback.
The optional path has a five-second shared deadline checked before/after provider
work and exposed through active callbacks; providers need their own bounded transport
timeout. It does not promise to interrupt a provider that blocks without respecting
that contract. Provider implementations must preserve existing ModelCallBudgetScope.
Raw text/vectors are not persisted by this adapter. `truthVerified=false` and mode
identify retrieval observations, not learned-model quality.

No DB migration or runtime configuration is added. To use a future trusted provider,
construct an encoder and a matching versioned sparse index adapter and compare it with
the labelled [retrieval quality harness](retrieval-quality-evaluation.md). Existing
BM25 must be enabled in the supplied baseline. BM25 unavailability remains an error,
not an invented successful learned result.

```
./mvnw -Pfull -Dtest=SparseRetrievalTest,SqliteVectorStoreTest,HybridRetrievalTest test
```

Tests cover provider absence, numeric validation, model mismatch, actual optional
adapter output, filter fallback, deadline, cancellation and shared-budget stops.
The provider fixture demonstrates the extension contract; no live learned sparse
model quality or external provider availability is claimed.
