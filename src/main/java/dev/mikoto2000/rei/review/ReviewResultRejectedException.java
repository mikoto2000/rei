package dev.mikoto2000.rei.review;

/** A review submission was validated but no assessment was accepted. */
public final class ReviewResultRejectedException
    extends dev.mikoto2000.rei.toolcontract.ToolResultRejectedException {
  public ReviewResultRejectedException(String guidance) {
    super("REVIEW_RESULT_INVALID: " + guidance);
  }

  public static ReviewResultRejectedException find(Throwable failure) {
    for (int depth = 0; failure != null && depth < 16; depth++, failure = failure.getCause())
      if (failure instanceof ReviewResultRejectedException rejected) return rejected;
    return null;
  }
}
