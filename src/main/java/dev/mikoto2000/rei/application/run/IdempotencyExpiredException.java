package dev.mikoto2000.rei.application.run;

/** 実行の受付と状態管理を継続できない重複受付の防止期限切れの状況を伝える例外。 */
public final class IdempotencyExpiredException extends RuntimeException {
  /** 既定の状態でIdempotencyExpiredExceptionを初期化する。 */
  public IdempotencyExpiredException() {
    super("Idempotency receipt has expired; automatic re-execution is forbidden");
  }
}
