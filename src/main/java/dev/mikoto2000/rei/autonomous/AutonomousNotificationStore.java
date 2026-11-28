package dev.mikoto2000.rei.autonomous;

import java.util.List;

/** Durable local notification intent; it has no inference or publication capability. */
public interface AutonomousNotificationStore {
  boolean isStopping();

  List<AutonomousDraftNotice> pendingNotices(int limit);

  boolean noticeDelivered(AutonomousDraftNotice notice);

  void recoverNotices();
}
