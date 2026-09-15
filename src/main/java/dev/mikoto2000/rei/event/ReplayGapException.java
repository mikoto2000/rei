package dev.mikoto2000.rei.event;
public final class ReplayGapException extends RuntimeException {
  public ReplayGapException() { super("Required run event history is no longer retained"); }
}
