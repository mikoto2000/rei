package dev.mikoto2000.rei.core.policy;

/** Raised only by pure delegation preflight, before approval or child execution. */
public final class InvalidToolArgumentsException extends IllegalArgumentException {
  public InvalidToolArgumentsException(String guidance){super(guidance);}
}
