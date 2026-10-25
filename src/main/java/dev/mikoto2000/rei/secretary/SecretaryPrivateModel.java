package dev.mikoto2000.rei.secretary;

/** Explicit destination for private drafting; absence never means use the conversation model. */
public interface SecretaryPrivateModel {
  record Descriptor(String endpoint, String fingerprint) {}

  Descriptor privateSecretaryDescriptor();
}
