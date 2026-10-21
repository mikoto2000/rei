package dev.mikoto2000.rei.http;

/** HTTP通信取得の状況を観測し、記録や判断に必要な情報を提供する。 */
public interface HttpFetchObserver {
  /** 該当なし。 */
  HttpFetchObserver NONE = new HttpFetchObserver() {};

  /** 外部情報の取得に関する要求を処理する。 */
  default void request(boolean redirect) {}

  /** 対象の現在の状態を、照会用の情報として返す。 */
  default void status(int status) {}

  /** 外部情報の取得に関するバイト数を処理する。 */
  default void bytes(int count) {}

  /** 処理を継続できなかった理由を、失敗情報として表す。 */
  default void failure(HttpFetchException.Code code) {}

  /** 外部情報の取得に関するキャンセルを処理する。 */
  default void cancellation() {}
}
