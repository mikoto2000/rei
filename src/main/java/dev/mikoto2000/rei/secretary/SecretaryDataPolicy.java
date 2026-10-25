package dev.mikoto2000.rei.secretary;

/** Scrub contact details and reject detected secrets before a private-model request is captured. */
public interface SecretaryDataPolicy {
  String scrubSecretaryContent(String content);
}
