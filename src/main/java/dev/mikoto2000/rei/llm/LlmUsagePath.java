package dev.mikoto2000.rei.llm;

/** Operator-declared usage classification; it never authorizes external data transfer. */
public enum LlmUsagePath {
  LOCAL_LLM("local-llm"),
  API_BILLING("api-billing"),
  CHATGPT_PLAN("chatgpt-plan"),
  UNKNOWN("unknown");

  private final String id;

  LlmUsagePath(String id) {
    this.id = id;
  }

  @com.fasterxml.jackson.annotation.JsonValue
  public String id() {
    return id;
  }

  static LlmUsagePath configured(String value, LlmProvider provider, String field) {
    if (value == null) return provider == LlmProvider.CHATGPT_PLAN ? CHATGPT_PLAN : UNKNOWN;
    for (var path : values()) {
      if (path.id.equals(value.strip().toLowerCase(java.util.Locale.ROOT).replace('_', '-'))) {
        if ((provider == LlmProvider.CHATGPT_PLAN) != (path == CHATGPT_PLAN))
          throw new IllegalArgumentException(
              field + ": must match the configured authentication provider");
        return path;
      }
    }
    throw new IllegalArgumentException(field + ": unsupported usage path");
  }
}
