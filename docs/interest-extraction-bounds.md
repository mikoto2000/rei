# Background interest extraction bounds

Interest discovery samples whole recent user messages. A message-count limit does not bound their size: long messages could previously produce a background prompt with hundreds of thousands of tokens and keep the shared inference server busy through SDK retries.

Extraction now selects newest whole snippets that fit a complete prompt budget, including instructions, timestamps, conversation references, bounded past queries, and an omission notice. Oversized individual snippets are skipped; their text is never silently cut and stored conversation history is unchanged. If no snippet fits, extraction returns no candidates without a model request. Omitted evidence is not treated as absent user interest.

| Setting under `rei.interest` | Default | Environment variable |
| --- | --- | --- |
| `extraction-max-input-tokens` | 24000 | `REI_INTEREST_EXTRACTION_MAX_INPUT_TOKENS` |
| `extraction-timeout-seconds` | 120 | `REI_INTEREST_EXTRACTION_TIMEOUT_SECONDS` |
| `extraction-max-output-tokens` | 4096 | `REI_INTEREST_EXTRACTION_MAX_OUTPUT_TOKENS` |

Input uses conservative UTF-8 byte token units plus message framing, so multilingual text may admit less evidence than a provider tokenizer would. The prompt itself is checked after selection. This budget is independent of the foreground chat context window. Input values must be 1024–65536, timeouts 1–120 seconds, and output values 64–4096.

One cancellable streaming request shares a single deadline across all chunks, including reasoning. SDK request retries are disabled for this background extraction. Output uses the provider's existing parameter family and preserves any smaller configured limit. A length-terminated, oversized, empty, or tool-calling response is rejected, even if it contains a parseable partial JSON array. Inference failure still propagates to the existing discovery failure path; no automatic tool execution or background fallback request is introduced.

These bounds prevent this job from submitting an unbounded prompt or waiting twenty minutes, but do not prove the shared inference server can meet every latency target. Provider reasoning settings and hardware load still affect completion. Changes activate after restart.
