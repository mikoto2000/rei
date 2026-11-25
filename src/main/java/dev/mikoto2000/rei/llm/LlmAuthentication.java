package dev.mikoto2000.rei.llm;

/** Authentication contract; no credential values belong in this metadata. */
public enum LlmAuthentication {
  NONE("none"),
  API_KEY("api-key"),
  CHATGPT_OAUTH("chatgpt-oauth"),
  UNCONFIGURED("unconfigured"),
  LEGACY("legacy");

  private final String id;

  LlmAuthentication(String id) {
    this.id = id;
  }

  @com.fasterxml.jackson.annotation.JsonValue
  public String id() {
    return id;
  }

  static LlmAuthentication resolve(
      LlmProperties.Server server, LlmProvider provider, boolean named, String field) {
    String value = server == null ? null : server.getAuthentication();
    String key = server == null ? null : server.getApiKey();
    LlmAuthentication authentication;
    if (value == null) {
      if (!named && provider == LlmProvider.OPENAI_COMPATIBLE) return LEGACY;
      authentication =
          provider == LlmProvider.CHATGPT_PLAN
              ? CHATGPT_OAUTH
              : key == null || key.isBlank() ? UNCONFIGURED : API_KEY;
    } else {
      authentication =
          switch (value.strip().toLowerCase(java.util.Locale.ROOT).replace('_', '-')) {
            case "none" -> NONE;
            case "api-key" -> API_KEY;
            case "chatgpt-oauth" -> CHATGPT_OAUTH;
            default -> throw invalid(field + ".authentication", "unsupported authentication mode");
          };
    }
    if (provider == LlmProvider.CHATGPT_PLAN && authentication != CHATGPT_OAUTH
        || provider != LlmProvider.CHATGPT_PLAN && authentication == CHATGPT_OAUTH
        || provider == LlmProvider.OPENAI_API && authentication != API_KEY)
      throw invalid(field + ".authentication", "must match the configured provider contract");
    if (authentication == NONE && key != null)
      throw invalid(field + ".api-key", "is forbidden when authentication is none");
    if (authentication == API_KEY && (key == null || key.isBlank()))
      throw invalid(field + ".api-key", "must be configured for api-key authentication");
    return authentication;
  }

  private static IllegalArgumentException invalid(String field, String reason) {
    return new IllegalArgumentException(field + ": " + reason);
  }
}
