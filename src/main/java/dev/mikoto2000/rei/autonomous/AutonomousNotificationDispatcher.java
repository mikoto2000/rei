package dev.mikoto2000.rei.autonomous;

import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Bounded local outbox delivery; no inference or external publication capability. */
@Component
public final class AutonomousNotificationDispatcher implements AutoCloseable {
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(AutonomousNotificationDispatcher.class);
  private final AutonomousNotificationStore store;
  private final Supplier<AutonomousDraftNotifications> notifications;
  private final AtomicBoolean closed = new AtomicBoolean();

  @Autowired
  public AutonomousNotificationDispatcher(
      AutonomousNotificationStore store,
      ObjectProvider<AutonomousDraftNotifications> notifications) {
    this(store, notifications::getIfAvailable);
  }

  AutonomousNotificationDispatcher(
      AutonomousNotificationStore store, Supplier<AutonomousDraftNotifications> notifications) {
    this.store = Objects.requireNonNull(store);
    this.notifications = Objects.requireNonNull(notifications);
  }

  @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 5000)
  public synchronized void flush() {
    if (stopping()) return;
    AutonomousDraftNotifications inbox;
    List<AutonomousDraftNotice> pending;
    try {
      inbox = notifications.get();
      if (inbox == null) return;
      pending = store.pendingNotices(20);
    } catch (RuntimeException unavailable) {
      log.warn("Autonomous notice source remains pending");
      return;
    }
    for (var notice : pending) {
      if (stopping()) return;
      try {
        inbox.deliver(notice);
        store.noticeDelivered(notice);
      } catch (RuntimeException unavailable) {
        log.warn("Autonomous notice delivery remains pending");
      }
    }
  }

  private boolean stopping() {
    return closed.get() || store.isStopping();
  }

  @Override
  @PreDestroy
  public void close() {
    closed.set(true);
  }
}
