package dev.mikoto2000.rei.secretary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.storage.SqliteSecretaryDatabase;
import dev.mikoto2000.rei.storage.StorageMigrationCoordinator;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

@Tag("integration")
class SecretaryBriefingsTest {
  @TempDir Path root;
  final Clock clock = Clock.fixed(Instant.parse("2026-10-26T10:00:00Z"), ZoneId.of("Asia/Tokyo"));

  SecretaryFollowups service() throws Exception {
    try (var migration = new StorageMigrationCoordinator(root)) {
      migration.prepare();
    }
    var db = new SqliteSecretaryDatabase(root.toString());
    return new SecretaryFollowups(db, new SecretaryLedger(db, clock), clock, n -> {}, true);
  }

  @Test
  void generationIsImmutableIdempotentAndPersistsAcrossRestart() throws Exception {
    String id;
    try (var service = service()) {
      var first = service.generate("p", null, false, "manual-1");
      id = first.id();
      new SecretaryLedger(new SqliteSecretaryDatabase(root.toString()), clock)
          .create(
              "p", "changed", "new task", SecretaryTask.Intent.TODO, null, 1, List.of(), List.of());
      assertEquals(first, service.generate("p", null, false, "manual-1"));
      assertTrue(first.report().sections().get("important").isEmpty());
      assertEquals(1, service.report("p", null, false).sections().get("important").size());
      assertThrows(
          SecretaryConflictException.class, () -> service.generate("p", null, true, "manual-1"));
      assertEquals(1, service.briefingHistory("p", 10).size());
    }
    try (var service = service()) {
      assertEquals(id, service.briefing("p", id).id());
      assertThrows(IllegalArgumentException.class, () -> service.briefing("other", id));
      assertEquals(1, service.deleteBriefings("p"));
      assertTrue(service.briefingHistory("p", 10).isEmpty());
    }
  }

  @Test
  void schedulerDoesNotRegenerateSourcesEveryPollOrAfterRestart() throws Exception {
    var reads = new AtomicInteger();
    var beans = new DefaultListableBeanFactory();
    beans.registerSingleton(
        "source",
        (SecretaryContext)
            (p, d, z) -> {
              reads.incrementAndGet();
              return List.of();
            });
    try (var service = service()) {
      service.setContexts(beans.getBeanProvider(SecretaryContext.class));
      service.configure(
          new SecretaryFollowups.Settings(
              "p", true, true, "08:00", "18:30", "Asia/Tokyo", 86400, 3, "INBOX"));
      service.poll();
      service.poll();
      assertEquals(2, reads.get());
      assertEquals(2, service.briefingHistory("p", 10).size());
    }
    try (var service = service()) {
      service.setContexts(beans.getBeanProvider(SecretaryContext.class));
      service.poll();
      assertEquals(2, reads.get());
    }
  }

  @Test
  void unavailableProviderIsPartialAndUnknownTimeDoesNotBecomeTonightWork() throws Exception {
    var beans = new DefaultListableBeanFactory();
    beans.registerSingleton(
        "broken",
        (SecretaryContext)
            (p, d, z) -> {
              throw new IllegalStateException();
            });
    try (var service = service()) {
      service.setContexts(beans.getBeanProvider(SecretaryContext.class));
      var report = service.report("p", null, true);
      assertTrue(report.partial());
      assertTrue(report.sections().get("next").isEmpty());
      assertTrue(report.cautions().stream().anyMatch(s -> s.contains("業務予定は不明")));
      assertTrue(
          report.evidence().stream()
              .anyMatch(e -> e.reliability() == SecretaryContext.Reliability.UNKNOWN));
    }
  }

  @Test
  void separateKeysPermitManualRegenerationAndExplicitDatesArePreserved() throws Exception {
    try (var service = service()) {
      var date = LocalDate.of(2026, 10, 25);
      service.generate("p", date, false, "one");
      var second = service.generate("p", date, false, "two");
      assertEquals(date, second.report().date());
      assertEquals(2, service.briefingHistory("p", 10).size());
    }
  }

  @Test
  void evidenceKeepsCertaintySourcesAndOnlyExplicitAvailabilityOffersAtMostThreeChoices()
      throws Exception {
    var beans = new DefaultListableBeanFactory();
    beans.registerSingleton(
        "source",
        (SecretaryContext)
            (p, d, z) ->
                List.of(
                    new SecretaryContext.Item(
                        "saved",
                        "one",
                        "draft",
                        "READY",
                        "proposal",
                        SecretaryContext.Reliability.PROPOSAL,
                        List.of("job:one")),
                    new SecretaryContext.Item(
                        "saved",
                        "one",
                        "draft",
                        "READY",
                        "proposal",
                        SecretaryContext.Reliability.PROPOSAL,
                        List.of("job:one"))));
    try (var service = service()) {
      service.setContexts(beans.getBeanProvider(SecretaryContext.class));
      var db = new SqliteSecretaryDatabase(root.toString());
      var ledger = new SecretaryLedger(db, clock);
      for (int i = 0; i < 5; i++)
        ledger.create(
            "p",
            "t" + i,
            "task" + i,
            SecretaryTask.Intent.TODO,
            null,
            3,
            List.of("prep-minutes:10"),
            List.of());
      assertTrue(service.report("p", null, true).sections().get("next").isEmpty());
      var life =
          new dev.mikoto2000.rei.life.LifeContext(
              new dev.mikoto2000.rei.life.LifeContext.Store() {
                public dev.mikoto2000.rei.life.LifeContext.Settings load() {
                  return null;
                }

                public void save(
                    dev.mikoto2000.rei.life.LifeContext.Settings next, long expected) {}
              },
              clock);
      var old = life.settings();
      life.configure(
          new dev.mikoto2000.rei.life.LifeContext.Settings(
              0,
              old.zone(),
              old.workDays(),
              old.workStart(),
              old.workEnd(),
              List.of(),
              List.of(),
              List.of(
                  new dev.mikoto2000.rei.life.LifeContext.Window(
                      java.util.Set.of(java.time.DayOfWeek.MONDAY), "18:00", "20:00")),
              List.of(),
              null));
      service.setLifeContext(life);
      var report = service.report("p", null, true);
      assertEquals(3, report.sections().get("next").size());
      assertEquals(1, report.context().size());
      assertTrue(
          report.evidence().stream()
              .anyMatch(e -> e.reliability() == SecretaryContext.Reliability.USER_REGISTERED));
      assertTrue(report.evidence().stream().anyMatch(e -> e.references().contains("job:one")));
    }
  }

  @Test
  void dateAndZoneChangesCatchUpOnlyCurrentDateWithoutRepeatingExistingDate() throws Exception {
    var time = new SecretaryFollowupsTest.TestClock();
    time.now = Instant.parse("2026-10-26T10:00:00Z");
    service().close();
    var db = new SqliteSecretaryDatabase(root.toString());
    try (var service =
        new SecretaryFollowups(db, new SecretaryLedger(db, time), time, n -> {}, true)) {
      service.configure(
          new SecretaryFollowups.Settings(
              "p", true, true, "08:00", "", "Asia/Tokyo", 86400, 3, "INBOX"));
      service.poll();
      service.configure(
          new SecretaryFollowups.Settings("p", true, true, "08:00", "", "UTC", 86400, 3, "INBOX"));
      service.poll();
      assertEquals(1, service.briefingHistory("p", 100).size());
      time.now = time.now.plusSeconds(3 * 86400);
      service.poll();
      assertEquals(2, service.briefingHistory("p", 100).size());
      assertEquals(
          LocalDate.of(2026, 10, 29), service.briefingHistory("p", 100).getFirst().report().date());
    }
  }

  @Test
  void scheduledBriefingIsStoredButHeldDuringWorkWithoutConsumingDeliveryRetries()
      throws Exception {
    var time = new SecretaryFollowupsTest.TestClock();
    service().close();
    var db = new SqliteSecretaryDatabase(root.toString());
    var life =
        new dev.mikoto2000.rei.life.LifeContext(
            new dev.mikoto2000.rei.storage.SqliteLifeContextStore(root.toString()), time);
    try (var service =
        new SecretaryFollowups(
            db,
            new SecretaryLedger(db, time),
            time,
            n -> {
              throw new AssertionError("work interruption");
            },
            true)) {
      service.setLifeContext(life);
      service.configure(
          new SecretaryFollowups.Settings(
              "p", true, true, "08:00", "", "Asia/Tokyo", 86400, 3, "INBOX"));
      service.poll();
      service.poll();
      assertEquals(1, service.briefingHistory("p", 100).size());
      assertEquals("HELD", service.history("p", 100).getFirst().status());
      assertEquals(0, service.history("p", 100).getFirst().attempts());
    }
  }
}
