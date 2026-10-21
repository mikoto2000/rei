package dev.mikoto2000.rei.http;

/**
 * 外部情報の取得を継続できないHTTP通信取得の状況を伝える例外。
 *
 * <p>Fetch failures never contain a URL, body or credential in their public message.
 */
public class HttpFetchException extends IllegalStateException {
  /** 外部情報の取得におけるコードの選択肢。 */
  public enum Code {
    /** URL否定許可。 */
    URL_NOT_ALLOWED,
    /** 機能の接続上限。 */
    WIRE_LIMIT,
    /** 復元済み上限。 */
    DECODED_LIMIT,
    /** 未対応文字コード。 */
    UNSUPPORTED_ENCODING,
    /** 不正内容の種類。 */
    INVALID_CONTENT_TYPE,
    /** 転送先上限。 */
    REDIRECT_LIMIT,
    /** 接続タイムアウト。 */
    CONNECT_TIMEOUT,
    /** 読み取りタイムアウト。 */
    READ_TIMEOUT,
    /** 合計タイムアウト。 */
    TOTAL_TIMEOUT,
    /** ネットワークエラー。 */
    NETWORK_ERROR,
    /** 要求予算。 */
    REQUEST_BUDGET,
    /** 取得拒否済み。 */
    FETCH_REJECTED,
    /** 引き継ぎ予算。 */
    TRANSFER_BUDGET,
    /** EXTRACTION上限。 */
    EXTRACTION_LIMIT,
    /** 出力予算。 */
    OUTPUT_BUDGET
  }

  /** コード。 */
  private final Code code;

  /** 指定された値や利用先を使って、HttpFetchExceptionを初期化する。 */
  public HttpFetchException(Code code) {
    super(code.name());
    this.code = code;
  }

  /** 指定された値や利用先を使って、HttpFetchExceptionを初期化する。 */
  public HttpFetchException(Code code, Throwable cause) {
    super(code.name(), cause);
    this.code = code;
  }

  /** 外部情報の取得で用いるコードを返す。 */
  public Code code() {
    return code;
  }
}
