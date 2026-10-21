package dev.mikoto2000.rei.feed;

/**
 * フィードの購読と収集を継続できないOPML取り込みの状況を伝える例外。
 *
 * <p>A file-level import error safe to present in the shell.
 */
public class OpmlImportException extends RuntimeException {
  /** 指定された値や利用先を使って、OpmlImportExceptionを初期化する。 */
  public OpmlImportException(String message) {
    super(message);
  }

  /** 指定された値や利用先を使って、OpmlImportExceptionを初期化する。 */
  public OpmlImportException(String message, Throwable cause) {
    super(message, cause);
  }
}
