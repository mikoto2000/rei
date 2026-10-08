package dev.mikoto2000.rei.http;

/** Fetch failures never contain a URL, body or credential in their public message. */
public class HttpFetchException extends IllegalStateException {
  public enum Code {
    URL_NOT_ALLOWED, WIRE_LIMIT, DECODED_LIMIT, UNSUPPORTED_ENCODING, INVALID_CONTENT_TYPE,
    REDIRECT_LIMIT, CONNECT_TIMEOUT, READ_TIMEOUT, TOTAL_TIMEOUT, NETWORK_ERROR
  }
  private final Code code;
  public HttpFetchException(Code code) { super(code.name()); this.code = code; }
  public HttpFetchException(Code code, Throwable cause) { super(code.name(), cause); this.code = code; }
  public Code code() { return code; }
}
