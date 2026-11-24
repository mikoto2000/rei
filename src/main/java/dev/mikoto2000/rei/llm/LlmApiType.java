package dev.mikoto2000.rei.llm;

/** Explicit wire protocol; never inferred from a hostname or model name. */
public enum LlmApiType {
  CHAT_COMPLETIONS,
  RESPONSES
}
