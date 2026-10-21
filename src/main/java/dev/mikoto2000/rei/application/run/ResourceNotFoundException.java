package dev.mikoto2000.rei.application.run;

/** 実行の受付と状態管理を継続できない補助資料否定見つかったの状況を伝える例外。 */
public final class ResourceNotFoundException extends RuntimeException {
  /** 指定された値や利用先を使って、ResourceNotFoundExceptionを初期化する。 */
  public ResourceNotFoundException(String resource) {
    super(resource + " not found");
  }
}
