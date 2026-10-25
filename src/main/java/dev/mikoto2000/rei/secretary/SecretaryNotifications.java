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
      Instant created) {
    public dev.mikoto2000.rei.life.LifeContext.NotificationClass category() {
      return switch (kind) {
        case "EMERGENCY" -> dev.mikoto2000.rei.life.LifeContext.NotificationClass.EMERGENCY;
        case "BRIEFING", "REVIEW" -> dev.mikoto2000.rei.life.LifeContext.NotificationClass.BRIEFING;
        case "SUGGESTION" -> dev.mikoto2000.rei.life.LifeContext.NotificationClass.LOW;
        default -> dev.mikoto2000.rei.life.LifeContext.NotificationClass.NORMAL;
      };
    }
  }

  void deliver(Notice notice);
}
