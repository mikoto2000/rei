package dev.mikoto2000.rei.application.run;

/** 実行の受付と状態管理を継続できない実行否定見つかったの状況を伝える例外。 */
public final class RunNotFoundException extends RuntimeException {
  /** 既定の状態でRunNotFoundExceptionを初期化する。 */
  public RunNotFoundException() {
    super("Run not found");
  }
}
