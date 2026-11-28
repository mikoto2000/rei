package dev.mikoto2000.rei.autonomous;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class AutonomousNotificationDispatcherTest {
  private static final Instant AT = Instant.parse("2026-11-28T09:00:00Z");
  private final FakeStore store = new FakeStore();
  private final FakeInbox inbox = new FakeInbox();

  private AutonomousDraftNotice notice(String id) {
    var notice = new AutonomousDraftNotice(id, true, AT);
    store.pending.put(id, notice);
    return notice;
  }

  private AutonomousNotificationDispatcher worker() {
    return new AutonomousNotificationDispatcher(store, () -> inbox);
  }

  @Test
  void successAcknowledgesOnlyAfterTheInboxAcceptsTheSavedNotice() {
    var notice = notice("one");
    inbox.before = n -> assertThat(store.pending).containsEntry("one", n);
    var worker = worker();
    worker.flush();
    assertThat(inbox.attempts).containsExactly(notice);
    assertThat(store.pending).isEmpty();
    worker.flush();
    assertThat(inbox.attempts).hasSize(1);
  }

  @Test
  void failedDeliveryStaysPendingAndRestartRetriesTheSameNotificationId() {
    var notice = notice("one");
    inbox.fail = true;
    assertThatCode(() -> worker().flush()).doesNotThrowAnyException();
    assertThat(store.pending).containsEntry("one", notice);
    inbox.fail = false;
    worker().flush();
    assertThat(store.pending).isEmpty();
    assertThat(inbox.attempts).hasSize(2);
    assertThat(inbox.attempts).allMatch(n -> n.notificationId().equals(notice.notificationId()));
    assertThat(inbox.items).hasSize(1);
  }

  @Test
  void failureAfterInboxPersistenceCanReplayWithoutGrowingTheInbox() {
    notice("one");
    store.failAck = true;
    worker().flush();
    assertThat(inbox.items).hasSize(1);
    assertThat(store.pending).hasSize(1);
    store.failAck = false;
    worker().flush();
    assertThat(store.pending).isEmpty();
    assertThat(inbox.items).hasSize(1);
  }

  @Test
  void aLateOpenAcknowledgmentCannotConsumeAConcurrentResolution() {
    var open = notice("one");
    var close = new AutonomousDraftNotice("one", false, AT);
    inbox.before =
        n -> {
          if (n.reviewRequired()) store.pending.put("one", close);
        };
    worker().flush();
    assertThat(store.pending).containsEntry("one", close);
    worker().flush();
    assertThat(inbox.attempts).containsExactly(open, close);
    assertThat(store.pending).isEmpty();
  }

  @Test
  void oneTickHasABoundedBatchAndOneFailureDoesNotPreventOtherDeliveries() {
    for (int i = 0; i < 41; i++) notice("draft-" + i);
    inbox.before =
        n -> {
          if (n.draftId().equals("draft-0")) throw new IllegalStateException("PRIVATE");
        };
    var worker = worker();
    worker.flush();
    assertThat(store.requestedLimit).isEqualTo(20);
    assertThat(inbox.attempts).hasSize(20);
    assertThat(store.pending).hasSize(22);
    worker.flush();
    worker.flush();
    assertThat(store.pending).hasSize(1).containsKey("draft-0");
    assertThat(inbox.items).hasSize(40);
  }

  @Test
  void missingAdapterShutdownAndReadFailureKeepTheDurableIntent() {
    notice("one");
    new AutonomousNotificationDispatcher(store, () -> null).flush();
    assertThat(store.reads).isZero();
    var worker = worker();
    worker.close();
    worker.flush();
    assertThat(store.reads).isZero();
    store.stopping = true;
    worker().flush();
    assertThat(store.reads).isZero();
    store.stopping = false;
    store.failRead = true;
    assertThatCode(() -> worker().flush()).doesNotThrowAnyException();
    assertThat(store.pending).hasSize(1);
    assertThat(inbox.attempts).isEmpty();
  }

  private static final class FakeStore implements AutonomousNotificationStore {
    final Map<String, AutonomousDraftNotice> pending = new LinkedHashMap<>();
    boolean stopping, failAck, failRead;
    int reads, requestedLimit;

    public boolean isStopping() {
      return stopping;
    }

    public List<AutonomousDraftNotice> pendingNotices(int limit) {
      reads++;
      requestedLimit = limit;
      if (failRead) throw new IllegalStateException("PRIVATE_DB_DIAGNOSTIC");
      return pending.values().stream().limit(limit).toList();
    }

    public boolean noticeDelivered(AutonomousDraftNotice notice) {
      if (failAck) throw new IllegalStateException("PRIVATE_DB_DIAGNOSTIC");
      return pending.remove(notice.draftId(), notice);
    }

    public void recoverNotices() {
      throw new AssertionError("Store startup owns recovery");
    }
  }

  private static final class FakeInbox implements AutonomousDraftNotifications {
    final List<AutonomousDraftNotice> attempts = new ArrayList<>();
    final Map<String, AutonomousDraftNotice> items = new LinkedHashMap<>();
    boolean fail;
    Consumer<AutonomousDraftNotice> before = n -> {};

    public void deliver(AutonomousDraftNotice notice) {
      attempts.add(notice);
      before.accept(notice);
      if (fail) throw new IllegalStateException("PRIVATE_PROVIDER_DIAGNOSTIC");
      items.put(notice.notificationId(), notice);
    }

    public List<Item> listNotices(int limit) {
      throw new AssertionError("Delivery cannot query user inbox");
    }

    public Item acknowledgeNotice(String id) {
      throw new AssertionError("Delivery cannot acknowledge for the user");
    }
  }
}
