package dev.mikoto2000.rei.application.run;
public final class IdempotencyConflictException extends RuntimeException {
  public IdempotencyConflictException(){super("Idempotency key belongs to a different request");}
}
