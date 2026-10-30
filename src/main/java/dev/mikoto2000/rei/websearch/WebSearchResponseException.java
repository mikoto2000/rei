package dev.mikoto2000.rei.websearch;

/** 検索結果の代わりに返された認証ページを、本文を含めずに通知する。 */
public final class WebSearchResponseException extends IllegalStateException
    implements dev.mikoto2000.rei.toolcontract.ExternalReadFailure {
  /** 検索先から返されたHTTPステータス。 */
  private final int status;

  /** 応答本文や検索語を保持せず、検索先の制限を通知する。 */
  public WebSearchResponseException(int status) {
    super("Web search provider returned a bot challenge.");
    this.status = status;
  }

  @Override
  public Integer httpStatus() {
    return status;
  }

  @Override
  public String diagnosticCode() {
    return "BOT_CHALLENGE";
  }

  @Override
  public String diagnosticMessage() {
    return "検索先がボット確認を要求したため、検索結果を取得できませんでした。";
  }
}
