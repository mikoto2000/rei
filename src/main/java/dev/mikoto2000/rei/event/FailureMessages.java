package dev.mikoto2000.rei.event;

/** Bounded, redacted diagnostics shared by commands and terminal clients. */
public final class FailureMessages {
  private FailureMessages() {}

  public static String describe(Throwable error) {
    if (error == null) return "原因の詳細がありません。";
    var seen =
        java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
    var reasons = new java.util.LinkedHashSet<String>();
    for (var current = error;
        current != null && seen.size() < 8 && seen.add(current);
        current = current.getCause()) {
      String message = current.getMessage();
      reasons.add(
          text(
              message == null || message.isBlank() ? current.getClass().getSimpleName() : message));
    }
    return text(String.join(" → ", reasons));
  }

  public static String text(String reason) {
    if (reason == null || reason.isBlank()) return "原因の詳細がありません。";
    String safe =
        CredentialRedactor.redact(reason)
            .replaceAll("[\\p{Cntrl}]+", " ")
            .replaceAll("\\s+", " ")
            .trim();
    return safe.length() <= 600 ? safe : safe.substring(0, 599) + "…";
  }
}
