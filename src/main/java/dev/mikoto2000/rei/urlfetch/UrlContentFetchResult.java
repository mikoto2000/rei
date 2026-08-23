package dev.mikoto2000.rei.urlfetch;

public record UrlContentFetchResult(
    boolean success,
    String content,
    String errorType,
    String errorMessage,
    Integer statusCode,
    String contentType) {

  public UrlContentFetchResult(boolean success, String content, String errorType, String errorMessage,
      Integer statusCode) {
    this(success, content, errorType, errorMessage, statusCode, null);
  }

  public static UrlContentFetchResult success(String content) {
    return success(content, null);
  }

  public static UrlContentFetchResult success(String content, String contentType) {
    return new UrlContentFetchResult(true, content, null, null, null, contentType);
  }

  public static UrlContentFetchResult failure(String errorType, String errorMessage) {
    return new UrlContentFetchResult(false, null, errorType, errorMessage, null, null);
  }

  public static UrlContentFetchResult failure(String errorType, String errorMessage, Integer statusCode) {
    return new UrlContentFetchResult(false, null, errorType, errorMessage, statusCode, null);
  }
}
