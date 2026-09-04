package dev.mikoto2000.rei.summarize;

public record UrlFetch(boolean success, String content, String errorType, String errorMessage, Integer statusCode) {

  public static UrlFetch success(String content) {
    return new UrlFetch(true, content, null, null, null);
  }

  public static UrlFetch failure(String errorType, String errorMessage) {
    return new UrlFetch(false, null, errorType, errorMessage, null);
  }

  public static UrlFetch failure(String errorType, String errorMessage, Integer statusCode) {
    return new UrlFetch(false, null, errorType, errorMessage, statusCode);
  }
}
