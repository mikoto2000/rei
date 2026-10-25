package dev.mikoto2000.rei.secretary;

import java.time.Instant;

/** Idempotent local notification ingress. External delivery belongs to the existing outbox. */
@FunctionalInterface
public interface SecretaryNotifications {
  record Notice(
      String id,
      String projectId,
      String taskId,
      String kind,
      String message,
      int priority,
      String destination,
      Instant created) {}

  void deliver(Notice notice);
}
