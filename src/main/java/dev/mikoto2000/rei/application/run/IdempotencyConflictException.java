package dev.mikoto2000.rei.application.run;

/** 実行の受付と状態管理を継続できない重複受付の防止競合の状況を伝える例外。 */
public final class IdempotencyConflictException extends RuntimeException {
  /** 既定の状態でIdempotencyConflictExceptionを初期化する。 */
  public IdempotencyConflictException() {
    super("Idempotency key belongs to a different request");
  }
}
