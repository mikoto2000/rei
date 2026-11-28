package dev.mikoto2000.rei.autonomous;

/** A stale revision, expired draft or lost reservation cannot mutate current state. */
public final class AutonomousConflictException extends RuntimeException {
  @java.io.Serial private static final long serialVersionUID = 1L;
}
