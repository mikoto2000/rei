package dev.mikoto2000.rei.secretary;

/** Admission to the existing notification outbox; holding is not a failed delivery attempt. */
public interface SecretaryInterruptions {
  enum Decision {
    ALLOW,
    HOLD,
    SUPPRESS
  }

  record Admission(Decision decision, String reason) {}

  Admission admit(SecretaryNotifications.Notice notice);

  int release(String project);
}
