package dev.mikoto2000.rei.application.run;
public final class IdempotencyExpiredException extends RuntimeException {
  public IdempotencyExpiredException(){super("Idempotency receipt has expired; automatic re-execution is forbidden");}
}
