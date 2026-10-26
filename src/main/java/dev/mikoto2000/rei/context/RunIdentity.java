package dev.mikoto2000.rei.context;

/** Primitive run provenance shared by work records without depending on the chat executor. */
public final class RunIdentity {
  private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

  private RunIdentity() {}

  public static String currentRunId() {
    return CURRENT.get();
  }

  public static void setCurrentRunId(String runId) {
    if (runId == null) CURRENT.remove();
    else CURRENT.set(runId);
  }
}
