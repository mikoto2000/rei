package dev.mikoto2000.rei.activity;

import java.util.Set;

/** Provider error values are untrusted: emit only fixed vocabulary, never redacted free text. */
record ActivityProviderDiagnostic(String code, String param, String type, String hint) {
  private static final Set<String> CODES =
      Set.of(
          "invalid_json_schema",
          "unsupported_parameter",
          "unsupported_value",
          "invalid_parameter",
          "invalid_value",
          "invalid_request_error",
          "invalid_image",
          "invalid_image_format",
          "invalid_base64_image",
          "image_too_large",
          "context_length_exceeded",
          "model_not_found",
          "insufficient_quota");
  private static final Set<String> PARAMS =
      Set.of(
          "model",
          "messages",
          "response_format",
          "response_format.json_schema",
          "response_format.json_schema.schema",
          "max_tokens",
          "max_completion_tokens",
          "temperature",
          "top_p",
          "reasoning_effort",
          "tools",
          "tool_choice",
          "stream");
  private static final Set<String> TYPES =
      Set.of(
          "invalid_request_error",
          "invalid_request",
          "authentication_error",
          "permission_error",
          "rate_limit_error",
          "server_error");

  static ActivityProviderDiagnostic unavailable() {
    return new ActivityProviderDiagnostic(
        "unavailable", "unavailable", "unavailable", "unavailable");
  }

  static ActivityProviderDiagnostic from(com.openai.errors.OpenAIServiceException error) {
    try {
      // Do not inspect Throwable.getMessage(): it may contain the entire HTTP body.
      var body = error.body().convert(com.fasterxml.jackson.databind.JsonNode.class);
      return from(body);
    } catch (RuntimeException malformed) {
      return unavailable();
    }
  }

  static ActivityProviderDiagnostic from(com.fasterxml.jackson.databind.JsonNode body) {
    // The SDK exposes the inner error object; accept an envelope as well.
    var error = body != null && body.has("error") ? body.get("error") : body;
    if (error == null || !error.isObject()) return unavailable();
    return new ActivityProviderDiagnostic(
        allowed(text(error, "code"), CODES), allowed(text(error, "param"), PARAMS),
        allowed(text(error, "type"), TYPES), hint(text(error, "message")));
  }

  private static String text(com.fasterxml.jackson.databind.JsonNode node, String field) {
    var value = node.get(field);
    return value != null && value.isTextual() ? value.textValue() : null;
  }

  private static String allowed(String value, Set<String> vocabulary) {
    return value == null ? "unavailable" : vocabulary.contains(value) ? value : "redacted";
  }

  /** Classify bounded message text; output is always a constant, even for echoed secrets. */
  private static String hint(String message) {
    if (message == null) return "unavailable";
    if (message.length() > 8192) return "redacted";
    var value = message.toLowerCase(java.util.Locale.ROOT);
    if (value.contains("invalid schema") || value.contains("invalid json schema")) {
      for (var keyword :
          java.util.List.of(
              "maxLength",
              "minLength",
              "minimum",
              "maximum",
              "required",
              "additionalProperties",
              "pattern",
              "$schema")) {
        var quoted = "'" + keyword.toLowerCase(java.util.Locale.ROOT) + "'";
        if (value.contains(quoted)) return "invalid_json_schema_" + keyword;
      }
      return "invalid_json_schema";
    }
    if (value.contains("unsupported parameter")) return "unsupported_parameter";
    if (value.contains("unsupported value")) return "unsupported_value";
    if (value.contains("does not support image") || value.contains("image input is not supported"))
      return "image_not_supported";
    if (value.contains("invalid image")) return "invalid_image";
    if (value.contains("maximum context length")) return "context_length_exceeded";
    return "unclassified";
  }
}
