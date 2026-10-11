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

  public Code code() {
    return code;
  }

  public static ChatGptException of(Code code) {
    return new ChatGptException(code);
  }
}
