package dev.mikoto2000.rei.summarize;

/** 情報の要約を継続できないSummarizationの状況を伝える例外。 */
public class SummarizationException extends RuntimeException {

  /** コード。 */
  private final String code;

  /** 指定された値や利用先を使って、SummarizationExceptionを初期化する。 */
  public SummarizationException(String code, String message) {
    super(message);
    this.code = code;
  }

  /** 指定された値や利用先を使って、SummarizationExceptionを初期化する。 */
  public SummarizationException(String code, String message, Throwable cause) {
    super(message, cause);
    this.code = code;
  }

  /** 情報の要約で用いるコードを返す。 */
  public String code() {
    return code;
  }
}
