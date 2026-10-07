# Claude Code continuation, proposals and parallel review

These extensions reuse External Agent authorization, history, shared Run/Goal budgets, bounded process execution,
single delegation claims, Change Sets and the same parallel worker pool as Codex. Native CLI flags, subscription authentication,
session storage and JSON parsing remain inside the Claude adapter.

```yaml
rei:
  external-agents:
    claude:
      enabled: true
      persist-sessions: false
      fix-proposals-enabled: false
      parallel-review-enabled: false
      parallel-review-timeout: 120s
      implementation-enabled: false
      implementation-test-command: ''
      implementation-test-timeout-seconds: 30
```

Enable individual extensions deliberately. Native Claude Code 2.1.286 or later is required.
The adapter preserves subscription/OAuth login and rejects API-key, API-helper, third-party and unknown authentication.
Inherited API/cloud overrides are removed. It uses `--safe-mode`, an empty built-in Tool list, MCP denial and strict empty MCP configuration,
empty setting sources, no Chrome integration, bounded turns/output/time and JSON over stdin. These constraints also apply during continuation and proposals.
Managed administrator policy remains part of native CLI behavior; Rei does not claim to override it.

Use `requestClaudeCodeContinueReview` only for an explicit current request to continue a Claude review, supplying the Rei review ID.
Opt-in persistence selects a fresh UUID and a private stable cwd outside the source repository, with a source-root hash marker.
Continuation uses the saved UUID rather than arbitrary transcript paths or a session picker. The CLI must confirm the selected UUID.
Rei retains only the UUID in review history; native transcript format is not parsed or copied into Rei memory.
Native persistence can retain previous bounded snapshots in Claude's own history. At most 128 private cwd markers are retained;
perform deliberate operator cleanup rather than automatically evicting potentially unknown attempts.

History requires the same provider/Project/root, successful completed parent and an unconsumed continuation claim.
An attempt consumes its parent even if the CLI fails or the process stops. `STARTED` and failed/unknown attempts are never automatically resumed.
CLI installation, login, missing native history or expired transcripts are reported as unavailable/failed outcomes, never simulated success.
The source snapshot is rechecked after every invocation; changed inputs invalidate its review evidence.

For an explicit Claude fix proposal, `requestClaudeCodeFixProposal` reads a saved successful review's current target.
The Tool-free CLI returns a bounded single-file exact-baseline proposal. Its path must belong to the supplied snapshot.
The parent creates an ordinary durable Change Set and returns its ID without applying it or trusting external test claims.
Inspect the Change Set diff and follow the existing explicit Apply policy. Re-review changed files in a later Run.

For explicit parallel Claude review, `requestParallelClaudeCodeReviews` accepts 1–4 independent requests with unique IDs.
It uses the common two-worker pool, one admitted batch, shared parent model budget and a 100ms–120s batch deadline.
Each item retains its own provider, saved outcome and evidence. Cancellation/timeout stops owned CLI processes;
partial or unknown outcomes do not authorize retry, fixes or Apply. One batch consumes the same external delegation as an ordinary review.

Optional `/agent claude implement <target>` uses the [common isolated implementation](isolated-external-implementation.md).
It always uses a fresh ephemeral Tool-free invocation, even when review persistence is enabled. Parent-selected tests, static patch checks,
source hashes, diff/commit receipts, independent semantic evaluation and exact provider/receipt/hash merge approval remain required.

The [official CLI reference](https://code.claude.com/docs/en/cli-reference) documents UUID session selection, resume, authentication status and Tool/configuration flags.
The [official session documentation](https://code.claude.com/docs/en/sessions) describes scripted resume and native transcript storage.
Validation uses deterministic CLI adapters, SQLite history and temporary real Git repositories; no paid model or login is executed.
