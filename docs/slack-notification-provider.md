# Slack notification provider

Slack is an opt-in adapter under the existing Attention Inbox/outbox. It reuses Inbox identity,
Project allowlisting, delivery Policy, bounded queue, persisted claims, restart reconciliation,
manual duplicate-risk acknowledgement and the three-attempt limit.
The Inbox remains the authoritative local notification record; acknowledging it grants no permission.

## Configuration

```yaml
rei:
  attention:
    delivery:
      enabled: true
      automatic: false
      provider: SLACK
      projects: PROJECT_UUID
    slack:
      enabled: true
      bot-token: ${REI_ATTENTION_SLACK_BOT_TOKEN}
      channel: C123ABCDE
      channels: C123ABCDE
```

Both enabled flags default false; provider defaults `WEBHOOK`, preserving existing configuration.
`projects` and `channels` are administrator allowlists. Model text cannot select destinations or credentials.
The bot needs `chat:write` and access to the selected channel. Only explicit channel IDs are supported;
user IDs that would create a direct message are rejected.
The production endpoint is fixed to `https://slack.com/api/chat.postMessage`; a numeric-loopback HTTP
endpoint with that API path is permitted for local fixtures. Redirects are not followed.
The credential is sent only in the Authorization header and redacted from configuration diagnostics.
The persisted destination hash includes credential identity, so account/channel changes block old queued sends.

## Receipts and recovery

The payload contains bounded notification metadata: kind, Project ID, Inbox ID and timestamp.
It excludes Inbox message, conversation, Run result, Tool arguments, repository paths and user content.
Markup and link/media unfurling are disabled. `client_msg_id` uses the stable Inbox UUID.
This is supplementary identification: network exactly-once delivery is not promised.

HTTP 200 is insufficient: `ok:true`, the configured channel and a valid message timestamp are required
for `SENT`. A provider receipt `channel:timestamp` is saved in the existing delivery row and survives restart.
Known rejections are `FAILED`; transport timeout, malformed/oversized response, contradictory receipt,
uncertain API errors and server failures are `UNKNOWN`. Response bodies and credentials are not saved.
Each attempt has a two-second wall-clock limit and an 8 KiB response limit.

At most one claim is in flight. Slack claims reserve a persistent one-second interval per destination.
429/ratelimited responses persist a bounded `Retry-After` delay (1–3,600 seconds, fallback 60).
Manual retry does not bypass that delay, and waiting does not consume another attempt.
The existing five-second scheduler tick is intentionally conservative. Providers never resend on their own.
Restart after a claim records `UNKNOWN`; uncertain delivery is never automatically replayed.

Use the existing `/attention delivery`, `/attention deliver`, `/attention retry-delivery ...
--acknowledge-duplicate-risk` and authenticated Attention delivery API. Receipts add `provider`,
`providerReceipt`, `retryNotBefore` while preserving previous response fields and constructors.
Policy must grant `NETWORK_WRITE` and `EXTERNAL_SIDE_EFFECT` for `deliverAttention`.

## Validation

Local HTTP/SQLite fixtures exercise actual Authorization/payload/receipt processing, malformed responses,
200 API errors, allowlists, disable switches, rate delays across restart, manual risk acknowledgement,
deduplication and existing Webhook compatibility. No real Slack workspace is contacted.

Protocol references: [chat.postMessage](https://docs.slack.dev/reference/methods/chat.postMessage/)
and [Web API rate limits](https://docs.slack.dev/apis/web-api/rate-limits/).
