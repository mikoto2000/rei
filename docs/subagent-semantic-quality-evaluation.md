# SubAgent semantic validation quality evaluation

`SemanticValidationQuality` is an explicit, bounded evaluation API using manually
labelled anonymous cases. It has no scheduled job, Spring bean, credential discovery,
or automatic paid model call. Existing runtime semantic validation is unchanged.

Fixture categories: correct, subtly wrong, unsupported, contradictory, insufficient
evidence. Expected ACCEPT/REJECT/ABSTAIN labels are retained only by the scorer;
the model adapter sends task, answer and captured observations to the existing
`SubAgentSemanticValidator`, never labels or expected verdicts. It preserves model
options, disables tools, uses one call and the supplied shared budget reservation.

Positive means rejecting a defective answer. Reports include TP/FP/FN/TN,
abstentions, decisions on expected-uncertain cases, coverage and per-case decisions.
Abstentions are separate from false negatives and correct decisions. Invalid judge
JSON becomes ABSTAIN; transport failures, cancellation, budget stops and timeouts
abort the report. The existing binary validator cannot express a native abstention;
insufficient-evidence cases expose that limitation through the labelled scorer.

Optional second provider receives the same cases independently. Agreement includes
all decisions, including shared abstention, and is agreement rather than correctness.
Provider identities are explicit. Every report has `truthVerified=false`.

Limits:128 unique cases, task8192/answer32768 characters,16 observations of16384
characters each, shared30 seconds checked between calls, model adapter10 seconds.
Providers must implement bounded transport timeouts. No raw verdict is persisted by
the scorer. Use anonymous, permitted inputs; reports contain only case IDs,
categories, labels and decisions. No DB or runtime configuration is added.

```sh
./mvnw -Pfull -Dtest=SemanticValidationQualityTest,SubAgentSemanticValidationTest,SubAgentEvidenceTest test
```

The deterministic test model exercises the production validator and writes
`target/evaluation/subagent-semantic-quality.json`. Its metrics verify harness
mechanics; they do not establish real-model quality. To evaluate a real provider,
explicitly supply its configured ChatModel/options, captured owner and shared
reservation to `model(...)`, and pass that Judge to `evaluate(...)`. Different
provider adapters can also implement Judge with explicit abstention.
