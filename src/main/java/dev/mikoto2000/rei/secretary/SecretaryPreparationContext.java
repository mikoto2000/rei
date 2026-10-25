package dev.mikoto2000.rei.secretary;

/** Captured, revocable consent and evidence for an external task's isolated draft. */
public interface SecretaryPreparationContext {
  record Evidence(String objective, String permission, String modelFingerprint) {}

  Evidence prepareExternal(SecretaryTask task);

  boolean allowsExternal(SecretaryTask task, String permission);
}
