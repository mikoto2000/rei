package dev.mikoto2000.rei.urlfetch;

public record UrlContentFetchResult(
    boolean success,
    String content,
    String errorType,
    String errorMessage,
    Integer statusCode) {

  public static UrlContentFetchResult success(String content) {
    return new UrlContentFetchResult(true, content, null, null, null);
  }

  public static UrlContentFetchResult failure(String errorType, String errorMessage) {
    return new UrlContentFetchResult(false, null, errorType, errorMessage, null);
  }

  public static UrlContentFetchResult failure(String errorType, String errorMessage, Integer statusCode) {
    return new UrlContentFetchResult(false, null, errorType, errorMessage, statusCode);
  }
}
