package dev.mikoto2000.rei.llm;

/** Keeps Rei's existing server-root configuration compatible with the official SDK. */
public final class OpenAiCompatibleEndpoint {
  private OpenAiCompatibleEndpoint() {}

  public static String baseUrl(String baseUrl) {
    if (baseUrl == null || baseUrl.isBlank()) return baseUrl;
    String normalized = baseUrl.strip().replaceAll("/+$", "");
    return normalized.endsWith("/v1") ? normalized : normalized + "/v1";
  }
}
