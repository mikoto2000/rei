package dev.mikoto2000.rei.feed;

public class FeedFetchException extends RuntimeException {

  private final Integer httpStatus;

  public FeedFetchException(String message, Integer httpStatus) {
    super(message);
    this.httpStatus = httpStatus;
  }

  public FeedFetchException(String message, Integer httpStatus, Throwable cause) {
    super(message, cause);
    this.httpStatus = httpStatus;
  }

  public Integer httpStatus() {
    return httpStatus;
  }
}
