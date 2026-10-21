package dev.mikoto2000.rei.paper;

/** 論文の調査と整理を継続できない論文の状況を伝える例外。 */
public class PaperException extends RuntimeException {
  /** 論文の調査と整理におけるコードの選択肢。 */
  public enum Code {
    /** 検索失敗。 */
    SEARCH_FAILED,
    /** 論文否定見つかった。 */
    PAPER_NOT_FOUND,
    /** 内容否定利用可能。 */
    CONTENT_NOT_AVAILABLE,
    /** PDF取得失敗。 */
    PDF_DOWNLOAD_FAILED,
    /** PDF入力の解釈失敗。 */
    PDF_PARSE_FAILED,
    /** 要約失敗。 */
    SUMMARY_FAILED,
    /** 翻訳失敗。 */
    TRANSLATION_FAILED,
    /** 不正検索条件。 */
    INVALID_QUERY,
    /** 不正論文参照先。 */
    INVALID_PAPER_REFERENCE,
    /** 提供元頻度制限された。 */
    PROVIDER_RATE_LIMITED,
    /** 提供元タイムアウト。 */
    PROVIDER_TIMEOUT,
    /** 保管資料保存失敗。 */
    LIBRARY_STORAGE_FAILED,
    /** 保管資料読み取り失敗。 */
    LIBRARY_READ_FAILED,
    /** 保管資料削除失敗。 */
    LIBRARY_DELETE_FAILED,
    /** 成果物否定見つかった。 */
    ARTIFACT_NOT_FOUND
  }

  /** コード。 */
  private final Code code;

  /** 指定された値や利用先を使って、PaperExceptionを初期化する。 */
  public PaperException(Code code, String message) {
    super(message);
    this.code = code;
  }

  /** 指定された値や利用先を使って、PaperExceptionを初期化する。 */
  public PaperException(Code code, String message, Throwable cause) {
    super(message, cause);
    this.code = code;
  }

  /** 論文の調査と整理で用いるコードを返す。 */
  public Code code() {
    return code;
  }
}
