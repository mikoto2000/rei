package dev.mikoto2000.rei.application.run;

public final class SessionConflictException extends RuntimeException {
  public SessionConflictException() { super("Session belongs to another project"); }
}
