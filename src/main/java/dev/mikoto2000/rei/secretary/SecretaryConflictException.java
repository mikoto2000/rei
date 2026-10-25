package dev.mikoto2000.rei.secretary;

/** A stale update must be retried after the caller reads the current revision. */
public final class SecretaryConflictException extends IllegalStateException {
  private static final long serialVersionUID = 1L;

  public SecretaryConflictException() {
    super("Task revision conflict");
  }
}
