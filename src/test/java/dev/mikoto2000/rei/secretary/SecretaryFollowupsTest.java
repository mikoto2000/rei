package dev.mikoto2000.rei.secretary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.storage.SqliteSecretaryDatabase;
import dev.mikoto2000.rei.storage.StorageMigrationCoordinator;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("integration")
class SecretaryFollowupsTest {
  @Test
  void aClockBucketBoundaryDoesNotShortenTheConfiguredRepeatInterval() throws Exception {
    clock.now = Instant.parse("2026-10-26T00:59:59Z");
    try (var service = service()) {
      task(new SecretaryLedger(db(), clock), "one");
      service.configure(
          new SecretaryFollowups.Settings("p", true, true, "", "", "Asia/Tokyo", 3600, 2, "INBOX"));
      service.poll();
      assertEquals(1, received.size());
      clock.now = clock.now.plusSeconds(1);
      service.poll();
      assertEquals(1, received.size());
      clock.now = clock.now.plusSeconds(3600);
      service.poll();
      assertEquals(2, received.size());
    }
  }

  @TempDir Path root;
  final TestClock clock = new TestClock();
  final List<SecretaryNotifications.Notice> received = new ArrayList<>();

  static final class TestClock extends Clock {
    Instant now = Instant.parse("2026-10-26T00:00:00Z");

    @Override
    public ZoneId getZone() {
      return ZoneId.of("Asia/Tokyo");
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  SecretaryDatabase db() throws Exception {
    try (var migration = new StorageMigrationCoordinator(root)) {
      migration.prepare();
    }
    return new SqliteSecretaryDatabase(root.toString());
  }

  SecretaryFollowups service() throws Exception {
    var db = db();
    return new SecretaryFollowups(db, new SecretaryLedger(db, clock), clock, received::add, true);
  }

  SecretaryFollowups.Settings settings(boolean enabled, boolean notifications) {
    return new SecretaryFollowups.Settings(
        "p", enabled, notifications, "09:00", "18:00", "Asia/Tokyo", 3600, 2, "INBOX");
  }

  SecretaryTask task(SecretaryLedger ledger, String key) {
    return ledger.create(
        "p",
        key,
        "release",
        SecretaryTask.Intent.TODO,
        LocalDate.of(2026, 10, 26),
        1,
        List.of(),
        List.of());
  }

  @Test
  void unconfiguredAndDisabledMonitoringDoesNotStartSchedulesOrNotify() throws Exception {
    var service = service();
    task(new SecretaryLedger(db(), clock), "one");
    service.poll();
    assertTrue(received.isEmpty());
    service.configure(settings(false, true));
    service.poll();
    assertTrue(received.isEmpty());
    assertFalse(service.report("p", null, false).sections().get("today").isEmpty());
    service.close();
  }

  @Test
  void deadlinesAndDailyReportsDeduplicateAcrossRestartAndTerminalWorkStopsMonitoring()
      throws Exception {
    var service = service();
    var ledger = new SecretaryLedger(db(), clock);
    var task = task(ledger, "one");
    service.configure(settings(true, true));
    service.poll();
    assertEquals(2, received.size()); // due reminder and 09:00 briefing
    service.close();
    var restarted = service();
    restarted.poll();
    assertEquals(2, received.size());
    ledger.transition("p", task.id(), 0, SecretaryTask.Status.DONE);
    clock.now = clock.now.plusSeconds(3601);
    restarted.poll();
    assertEquals(1, received.stream().filter(n -> n.kind().equals("COMPLETED")).count());
    int count = received.size();
    clock.now = clock.now.plusSeconds(3601);
    restarted.poll();
    assertEquals(count, received.size());
    assertTrue(restarted.history("p", 100).stream().allMatch(n -> n.status().equals("DELIVERED")));
    restarted.close();
  }

  @Test
  void dependencyFactsArePersistentMonotonicAndSeparateReleaseFromCompletion() throws Exception {
    var service = service();
    var ledger = new SecretaryLedger(db(), clock);
    var task = task(ledger, "one");
    ledger.update(
        "p",
        task.id(),
        0,
        task.title(),
        task.intent(),
        SecretaryTask.Status.WAITING,
        task.due(),
        1,
        List.of(),
        List.of("dependency:d1"));
    service.configure(settings(true, false));
    service.observeDependency("p", "d1", "COMPLETED", 2);
    service.observeDependency("p", "d1", "WAITING", 1);
    service.poll();
    assertEquals(SecretaryTask.Status.OPEN, ledger.get("p", task.id()).status());
    var next = ledger.get("p", task.id());
    ledger.update(
        "p",
        next.id(),
        next.revision(),
        next.title(),
        next.intent(),
        SecretaryTask.Status.WAITING,
        next.due(),
        1,
        List.of(),
        List.of("completion:dependency:d1"));
    service.close();
    var restarted = service();
    restarted.poll();
    assertEquals(SecretaryTask.Status.DONE, ledger.get("p", task.id()).status());
    assertTrue(received.isEmpty());
    restarted.close();
  }

  @Test
  void deliveryFailuresBackOffAndHaveFiniteRetryBudget() throws Exception {
    var db = db();
    var ledger = new SecretaryLedger(db, clock);
    task(ledger, "one");
    int[] calls = {0};
    var service =
        new SecretaryFollowups(
            db,
            ledger,
            clock,
            notice -> {
              calls[0]++;
              throw new IllegalStateException("offline");
            },
            true);
    service.configure(
        new SecretaryFollowups.Settings(
            "p", true, true, "", "", "Asia/Tokyo", 2592000, 2, "INBOX"));
    service.poll();
    service.poll();
    assertEquals(1, calls[0]);
    for (int i = 0; i < 8; i++) {
      clock.now = clock.now.plusSeconds(3600);
      service.poll();
    }
    assertEquals(5, calls[0]);
    assertEquals("FAILED", service.history("p", 100).getFirst().status());
    service.close();
  }

  @Test
  void reportsRespectLocalDatesAndWorkWithoutDataOrExternalProviders() throws Exception {
    var service = service();
    assertTrue(service.report("p", null, true).sections().get("completed").isEmpty());
    var ledger = new SecretaryLedger(db(), clock);
    var task = task(ledger, "one");
    ledger.transition("p", task.id(), 0, SecretaryTask.Status.DONE);
    assertEquals(
        1,
        service.report("p", LocalDate.of(2026, 10, 26), true).sections().get("completed").size());
    clock.now = Instant.parse("2026-10-26T15:05:00Z");
    assertEquals(LocalDate.of(2026, 10, 27), service.report("p", null, false).date());
    service.close();
  }
}
