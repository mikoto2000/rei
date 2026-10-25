package dev.mikoto2000.rei.secretary;

/** The existing policy is an additional restriction, never replaced by an autonomy level. */
@FunctionalInterface
public interface SecretaryExecutionPolicy {
  record Decision(boolean enabled, String decision, String fingerprint) {}

  Decision decision(String kind);
}
