# Explicit live environment E2E

Normal, integration, integration-only and full Maven profiles exclude `@Tag("live")`.
The separate `live-e2e` profile selects only eight LiveEnvironmentE2ETest methods.
Every flag defaults OFF; only explicit true enables a provider. No application runtime
config, DB migration, credential discovery, installation or login is performed.

```sh
./mvnw -Plive-e2e test
```

With no flags this reports eight reasoned SKIPS, zero live successes. It does not
establish CLI compatibility, login, service availability or real-model quality.
Set only the flags for the operations you intend to run:

| Flag | Additional environment | Operations / effects |
|---|---|---|
| REI_E2E_CODEX=true | REI_E2E_CODEX_ACCOUNT_READY=true; compatible existing codex CLI/login | review; persisted native review/resume; isolated implementation, independent fixed checks, explicit merge into a temporary fixture repository |
| REI_E2E_CLAUDE=true | REI_E2E_CLAUDE_ACCOUNT_READY=true; compatible existing Claude Code subscription/OAuth login | tool-free review/resume in private temporary native storage; structured proposal through existing TextChangeSet propose/apply into a temporary fixture |
| REI_E2E_PAPER=true | outbound public HTTPS available; Crossref needs no credential | existing SafePaperHttpClient + Crossref provider search of a fixed public query |
| REI_E2E_NOTIFICATION=true | REI_E2E_SLACK_BOT_TOKEN and REI_E2E_SLACK_CHANNEL | exactly one real Slack metadata-only message into the supplied allowlisted channel, through existing durable outbox claim/receipt |

ACCOUNT_READY is an operator attestation, not inferred from account presence. Existing
adapters still perform their own capability/authentication checks. Missing flags,
account attestation, compatible CLI capabilities, Claude subscription auth, or Slack
credential/channel yield explicit assumption reasons. Other rejected/failed/timeout
adapter results fail the test; wrong structured model output is never hidden as skip.
No raw model output, token, channel or credential is printed by harness assertions.

CLI work uses anonymous A.txt only, 60-second total/30-second idle, two-call ceiling,
1MiB output and no enabled tools/MCP. Token accounting is not a configured token limit.
Each test has a 180-second JUnit bound. Independent implementation recipe verifies
Git diff and exact expected text before a temporary-repository merge. Parent fixture
stays unchanged until that explicit merge. Resume must confirm the same native UUID.
Codex persistent review sessions use the adapter's existing native session storage;
operators should account for those retained anonymous sessions. Claude storage, Git
repositories, changes/outbox DBs and sources are confined to JUnit temporary directories.

Paper uses bounded public HTTP, ten-second timeout, no retry and three-result maximum.
Slack retains SENT only with an actual valid provider receipt; unknown transport
outcomes stay UNKNOWN and are not automatically replayed. The fixture DB is temporary,
so a failed live send must be inspected externally before a deliberate rerun; a rerun
can create a duplicate. Normal deterministic HTTP/outbox tests cover retry/recovery.

```sh
./mvnw -Pfull -Dtest=LiveE2EPolicyTest,CodexImplementationAdapterTest,ClaudeCodeExtensionsAdapterTest,IsolatedImplementationServiceTest,AttentionDeliveryTest,SlackNotificationProviderTest,SlackOutboxIntegrationTest test
```

Explicit live flags authorize only these documented fixture operations. Tests do not
modify the real application repository, production library/inbox or authentication.
