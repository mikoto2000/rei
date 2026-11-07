package dev.mikoto2000.rei.websearch;

/** 検索先のHTTPエラーを、応答本文や認証情報を含めずに保持する。 */
public final class WebSearchHttpException extends IllegalStateException
    implements dev.mikoto2000.rei.toolcontract.ExternalReadFailure {
  @java.io.Serial private static final long serialVersionUID = 46743597010692891L;

  /** 検索先から返されたHTTPステータス。 */
  private final int status;

  /** 検索先のHTTPステータスだけを保持する。 */
  public WebSearchHttpException(int status) {
    super("Web search failed with HTTP " + status);
    this.status = status;
  }

  /** 検索先から返されたHTTPステータスを返す。 */
  public int status() {
    return status;
  }

  @Override
  public Integer httpStatus() {
    return status;
  }
}
