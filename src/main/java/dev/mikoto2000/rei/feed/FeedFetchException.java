package dev.mikoto2000.rei.feed;

/** フィードの購読と収集を継続できないフィード取得の状況を伝える例外。 */
public class FeedFetchException extends RuntimeException {

  /** HTTP通信状態。 */
  private final Integer httpStatus;

  /** 指定された値や利用先を使って、FeedFetchExceptionを初期化する。 */
  public FeedFetchException(String message, Integer httpStatus) {
    super(message);
    this.httpStatus = httpStatus;
  }

  /** 指定された値や利用先を使って、FeedFetchExceptionを初期化する。 */
  public FeedFetchException(String message, Integer httpStatus, Throwable cause) {
    super(message, cause);
    this.httpStatus = httpStatus;
  }

  /** フィードの購読と収集で用いるHTTP通信状態を返す。 */
  public Integer httpStatus() {
    return httpStatus;
  }
}
