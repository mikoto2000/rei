# Episode retrieval fixture

Scope: unchanged legacy long-term memory retrieval versus episode lexical retrieval. Both receive explicit CLI context. One synthetic event; dates are query text, not tested filters. No LLM calls.

|Question|Legacy candidates|Episode candidates|Legacy microseconds|Episode microseconds|
|---|---:|---:|---:|---:|
|以前 CLI を別プロセスにした理由は？|1|1|30851|95960|
|その設計では何を代替案として検討した？|1|1|13095|5801|
|以前の方針から何が変更された？|1|1|12118|5573|
|その情報の原典は？|1|1|12345|4920|
|昨日決めたことを教えて|1|1|9557|5061|
|その機能は実装済み？ それとも提案だけ？|1|1|8961|3352|
|先月どのような設計変更をした？|1|1|10091|5148|

Answer accuracy, evidence match rate, stale-answer rate, unslept conversation success, actual model tokens and physical SQLite I/O: 未測定. Timings are single-run local observations, not a statistical benchmark.

## Interpretation

Recorded on Windows / Java 25 / SQLite JDBC 3.51.3.0 during local regression. The first query includes cold initialization. Concurrent regression activity, connection setup and filesystem caches affect these observations. No percentile, throughput or superiority claim is made from one run.

The legacy decision stores the conclusion. The episode additionally stores an alternative, reason and explicitly unknown outcome with per-claim evidence. That is a representation difference, not a measured answer-quality gain. `EpisodeFederatedSearchTest` verifies retrieval of a raw conversation before any Sleep and separation of short candidates from detailed claims; `EpisodeSearchTest` verifies unavailable sources are hidden. These deterministic checks are not a percentage success evaluation over a corpus.

The evaluation makes no external model calls or embeddings. Production extraction uses the existing MEMORY budget; its actual model token consumption has not been measured. Indexed ordinal / Run / event-ID lookups are covered by SQLite integration tests, but physical I/O and large-history latency are not measured.