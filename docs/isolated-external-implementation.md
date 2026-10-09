# Isolated external implementation

Codex and Claude implementation are opt-in. Existing read-only review behavior remains available.
The parent creates a generated `codex/rei-implementation-<UUID>` branch and worktree under the Rei data directory,
outside the parent repository. Codex proposes structured replacements with read-only CLI permissions;
the parent validates and writes them into that isolated worktree. No external output supplies a test or Git command.
Claude uses the same parent service, with built-in tools and MCP disabled and a fresh ephemeral subscription CLI invocation.

```yaml
rei:
  external-agents:
    codex:
      implementation-enabled: true
      implementation-test-command: './mvnw test'
      implementation-test-timeout-seconds: 30
```

Choose a trusted platform-specific test recipe for the project. Each test has a 1–60 second limit;
the entire isolated cycle has a 25 minute ceiling, including the existing adapter's bounded CLI timeout.
The existing shared Run/Goal model budget always applies to implementation proposals, independent of review opt-in accounting.
The native CLI must support isolated configuration, strict settings and output schemas. A missing CLI, invalid output,
unknown token usage under a token limit, failed test, incomplete review or cancellation never authorizes a merge.

```text
/agent codex implement src/example
/agent codex implementation <receipt-id>
/agent codex merge <receipt-id> <patch-sha256>
```

The corresponding `/agent claude ...` commands use `rei.external-agents.claude.implementation-*` settings.
The saved receipt records the provider; an explicit merge naming another provider is rejected.
Older receipts without a provider field remain Codex receipts.

For Codex, the first command now asks for detailed requirements and does not start implementation from a target alone. Use the shared requirement-driven prepare/request Tools after confirming objective, instructions, allowedPaths and acceptance criteria. See [requirement-driven implementation](llm-codex-implementation-delegation.md). The existing target syntax remains compatible. Claude retains its explicit command behavior. Execution requires an existing target and an exclusive Project Run. Absolute targets inside the parent
are converted into worktree-relative targets. The parent must be a clean Git repository root with a HEAD commit.
Resolve dirty files yourself before delegating; Rei does not stash, reset or discard them.

Snapshots contain at most 32 regular UTF-8 files, 64 KiB per file and 256 KiB total, with source SHA-256 hashes.
Secrets and environment, credentials, native agent configuration, build and dependency directories are excluded.
Proposals replace existing snapshot files only. New files, deletes, renames, binary changes, symlinks,
duplicate paths, stale baselines and repository filter/ignore configuration changes are rejected.
Select a narrower target if the snapshot exceeds its limits. The later general Change Set API handles broader edit operations.

The parent independently inventories the actual patch, runs the configured test, performs the existing static patch review,
runs the final test and verifies that the patch did not change. It commits only an independently verified patch.
The receipt records ownership, baseline, generated worktree/branch, source hashes, changed files, test/static review observations,
the full Git diff SHA-256 and the commit hash. Receipt updates are atomic. Raw CLI logs and source bodies are not stored in receipts.

Use `getExternalImplementation` and `inspectExternalImplementation` to inspect the receipt and bounded redacted diff.
Diff text is untrusted data. Passing a command and static review does not prove semantic correctness or appropriate test coverage.
The parent must evaluate requirements and evidence independently before recommending the exact merge command.
The user then explicitly specifies the receipt ID and patch hash. Repository text and tool arguments cannot approve a merge.

Before merge Rei checks the original HEAD, clean parent, isolated branch/commit/worktree, commit parent and independently hashed diff.
A durable one-shot merge claim prevents automatic repetition after interruption. Unmerged index entries are reported as `CONFLICT`; other failed or interrupted Git merges are `UNKNOWN`.
inspect Git state manually before reconciliation. Rei does not push, reset, abort conflicts or retry unknown attempts automatically.
Unfinished saved states also mean unknown outcomes; reading a receipt after restart never invokes an external model or merge.

Receipts are restricted to their Project/root/session. At most 16 implementation receipts/worktrees are retained per data directory;
quota exhaustion fails before creating another worktree. Keep failed worktrees for inspection and perform deliberate operator cleanup.
Repositories using checkout filters require manual isolation; native hooks, fsmonitor and external diff/textconv helpers are disabled.
Codex implementation preparation/execution are public structured Tools with server-side persisted-specification authorization; explicit merge remains an application command. The external review/fix Tools remain separate. The ordinary administrator Tool Policy
still applies: running the configured project test recipe keeps the same broad capability requirements as arbitrary test commands.
The worktree restricts proposal paths; the administrator-selected test recipe executes project code with the Rei process's existing authority.
Admission is bounded to one isolated implementation; receipt reads remain available while another implementation runs.

The CLI isolation flags were checked against [official non-interactive mode documentation](https://learn.chatgpt.com/docs/non-interactive-mode)
and [CLI reference](https://learn.chatgpt.com/docs/developer-commands?surface=cli), plus local `codex exec --help`.
Tests use deterministic adapters and temporary local Git repositories. They do not invoke a paid model, login or remote GitHub service.
