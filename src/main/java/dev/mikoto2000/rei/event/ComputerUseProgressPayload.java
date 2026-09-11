package dev.mikoto2000.rei.event;

/** Small metadata only: no screenshots, clipboard contents, or typed text. */
public record ComputerUseProgressPayload(int step, String phase, String action, String target,
    Integer x, Integer y, Double confidence, String reason) implements AgentEventPayload {
  public ComputerUseProgressPayload {
    phase = clean(phase); action = clean(action); target = clean(target); reason = clean(reason);
  }
  private static String clean(String text) {
    if (text == null) return null;
    text = CredentialRedactor.redact(text).replaceAll("[\\p{Cntrl}]", " ");
    return text.substring(0, Math.min(200, text.length()));
  }
}
