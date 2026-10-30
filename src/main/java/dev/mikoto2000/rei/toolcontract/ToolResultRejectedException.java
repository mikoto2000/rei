package dev.mikoto2000.rei.toolcontract;

/** A typed validation rejection with no accepted result or unknown side effect. */
public class ToolResultRejectedException extends IllegalArgumentException {
  public ToolResultRejectedException(String message) {
    super(message);
  }

  public static ToolResultRejectedException find(Throwable failure) {
    for (int depth = 0; failure != null && depth < 16; depth++, failure = failure.getCause())
      if (failure instanceof ToolResultRejectedException rejected) return rejected;
    return null;
  }
}
