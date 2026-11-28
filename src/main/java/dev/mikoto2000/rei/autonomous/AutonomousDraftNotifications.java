package dev.mikoto2000.rei.autonomous;

import java.time.Instant;
import java.util.List;

/** Local review inbox. Acknowledging a notice never changes or publishes its draft. */
public interface AutonomousDraftNotifications {
  record Item(String id, String draftId, String status, Instant createdAt) {}

  void deliver(AutonomousDraftNotice notice);

  List<Item> listNotices(int limit);

  Item acknowledgeNotice(String id);
}
