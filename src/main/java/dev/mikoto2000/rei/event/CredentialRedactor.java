package dev.mikoto2000.rei.event;

/** Compatibility boundary for the shared, event-independent credential masker. */
public final class CredentialRedactor {
  private CredentialRedactor() {}

  public static String redact(String value) {
    return dev.mikoto2000.rei.toolcontract.CredentialMask.redact(value);
  }
}
