package dev.mikoto2000.rei.chatgpt;

/** Diagnostics carry only app-defined codes, never remote bodies, URLs or exception causes. */
public final class ChatGptException extends RuntimeException {
  public enum Code {
    AUTH_REQUIRED,
    AUTH_INVALID,
    CONSENT_REQUIRED,
    MODEL_UNAVAILABLE,
    USAGE_LIMIT,
    USAGE_UNAVAILABLE,
    PERMISSION_DENIED,
    ROUTE_UNAVAILABLE,
    PROTOCOL,
    TRANSPORT,
    TIMEOUT,
    CANCELLED,
    RECEIVE_LIMIT,
    SEND_LIMIT,
    STORE_UNAVAILABLE,
    BUSY,
    VALIDATION_FAILED
  }

  private final Code code;

  public ChatGptException(Code code) {
    super("ChatGPT plan: " + code.name(), null, false, false);
    this.code = code;
  }

  private ChatGptException(Code code, String diagnostic) {
    super("ChatGPT plan: " + code.name() + " (" + diagnostic + ")", null, false, false);
    this.code = code;
  }

  static ChatGptException response(
      Code code,
      String operation,
      int status,
      String mediaType,
      int received,
      int limit,
      String apiCode) {
    // All strings here are local enum-like values; never include remote header/body text.
    String safeType =
        switch (mediaType) {
          case "application/json", "text/event-stream" -> mediaType;
          case "" -> "missing";
          default -> "unsupported";
        };
    String safeApiCode =
        switch (apiCode) {
          case "subscription_sharing_usage_limit_exceeded",
              "subscription_sharing_usage_unavailable",
              "subscription_sharing_unsupported_capability",
              "subscription_sharing_route_not_supported",
              "subscription_sharing_user_unavailable",
              "model_not_found",
              "model_not_available",
              "subscription_sharing_invalid_user",
              "subscription_sharing_user_not_eligible",
              "chatpass_v2_scope_not_authorized",
              "chatpass_v2_invalid_authorization_context" ->
              apiCode;
          default -> "";
        };
    return new ChatGptException(
        code,
        "operation="
            + operation
            + ", httpStatus="
            + status
            + ", contentType="
            + safeType
            + (received < 0 ? "" : ", receivedBytes=" + received)
            + ", receiveLimitBytes="
            + limit
            + (received > limit ? ", bodyTruncated=true" : "")
            + (safeApiCode.isEmpty() ? "" : ", apiErrorCode=" + safeApiCode));
  }

  public Code code() {
    return code;
  }

  public static ChatGptException of(Code code) {
    return new ChatGptException(code);
  }
}
