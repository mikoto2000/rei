package dev.mikoto2000.rei.llm;

/** Explicit server contract, independently of authentication and wire API. */
public enum LlmProvider {
  OPENAI_COMPATIBLE("openai-compatible"),
  OPENAI_API("openai-api"),
  CHATGPT_PLAN("chatgpt-plan");

  private final String id;

  LlmProvider(String id) {
    this.id = id;
  }

  @com.fasterxml.jackson.annotation.JsonValue
  public String id() {
    return id;
  }

  static LlmProvider configured(String value, String field) {
    if (value == null) return OPENAI_COMPATIBLE;
    for (var provider : values())
      if (provider.id.equals(value.strip().toLowerCase(java.util.Locale.ROOT).replace('_', '-')))
        return provider;
    throw new IllegalArgumentException(field + ": unsupported provider");
  }
}
