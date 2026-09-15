package dev.mikoto2000.rei.application.run;

public final class RunNotFoundException extends RuntimeException {
  public RunNotFoundException() { super("Run not found"); }
}
