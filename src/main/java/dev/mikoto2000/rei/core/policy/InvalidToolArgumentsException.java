package dev.mikoto2000.rei.core.policy;

/** Raised only by pure argument preflight, before approval or tool execution. */
public final class InvalidToolArgumentsException extends IllegalArgumentException {
  public enum Kind { VALIDATION, MODEL_SELECTION }
  private final Kind kind;
  public InvalidToolArgumentsException(String guidance){this(guidance,Kind.VALIDATION);}
  public InvalidToolArgumentsException(String guidance,Kind kind){super(guidance);this.kind=kind;}
  public Kind kind(){return kind;}
}
