package dev.mikoto2000.rei.secretary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.mikoto2000.rei.storage.SqliteSecretaryDatabase;
import dev.mikoto2000.rei.storage.StorageMigrationCoordinator;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

@Tag("integration")
class SecretaryMeetingPersistenceTest {
  @TempDir Path root;
  final Clock clock = Clock.fixed(Instant.parse("2026-10-31T09:00:00Z"), ZoneId.of("Asia/Tokyo"));
  SecretaryDatabase db;
  SecretaryLedger ledger;

  @BeforeEach
  void prepare() throws Exception {
    try (var migration = new StorageMigrationCoordinator(root)) {
      migration.prepare();
    }
    db = new SqliteSecretaryDatabase(root.toString());
    ledger = new SecretaryLedger(db, clock);
  }

  SecretaryTask task(String key) {
    return ledger.create(
        "p", key, "レビュー " + key, SecretaryTask.Intent.TODO, null, 2, List.of(), List.of());
  }

  SecretaryFollowups service() {
    return new SecretaryFollowups(
        db,
        ledger,
        clock,
        n -> {
          fail("No notifications");
        },
        false);
  }

  @Test
  void eveningDecisionSurvivesRestartAndMorningRechecksCompletionBeforeConfirming() {
    var a = task("a");
    var b = task("b");
    var date = LocalDate.of(2026, 10, 31);
    try (var evening = service()) {
      assertEquals(2, evening.report("p", date, true).meeting().sections().get("carryover").size());
      evening.confirmMeeting(
          "p",
          date,
          true,
          "evening",
          List.of("secretary-task:" + a.id(), "secretary-task:" + b.id()));
      assertTrue(evening.report("p", date, true).meeting().confirmed());
    }
    ledger.transition("p", b.id(), b.revision(), SecretaryTask.Status.DONE);
    try (var morning = service()) {
      var report = morning.report("p", date.plusDays(1), false);
      assertEquals(
          List.of("secretary-task:" + a.id()),
          report.meeting().sections().get("handoff").stream()
              .map(SecretaryMeeting.Work::id)
              .toList());
      morning.confirmMeeting(
          "p", date.plusDays(1), false, "morning", List.of("secretary-task:" + a.id()));
      assertNull(morning.report("p", date.plusDays(1), false).meeting().question());
      assertEquals(2, ledger.list("p", 0, 100).size());
    }
  }

  @Test
  void emptySelectionEndsMeetingAndIdempotencyRejectsChangedContent() {
    try (var service = service()) {
      var first = service.confirmMeeting("p", null, true, "empty", List.of());
      assertEquals(first, service.confirmMeeting("p", null, true, "empty", List.of()));
      assertTrue(service.report("p", null, true).meeting().confirmed());
      assertThrows(
          SecretaryConflictException.class,
          () -> service.confirmMeeting("p", null, false, "empty", List.of()));
    }
  }

  @Test
  void staleCancelledCrossProjectOrUnknownSelectionCannotBeSaved() {
    var t = task("a");
    ledger.transition("p", t.id(), t.revision(), SecretaryTask.Status.CANCELLED);
    try (var service = service()) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              service.confirmMeeting(
                  "p", null, true, "cancelled", List.of("secretary-task:" + t.id())));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              service.confirmMeeting(
                  "other", null, true, "other", List.of("secretary-task:" + t.id())));
      assertThrows(
          IllegalArgumentException.class,
          () -> service.confirmMeeting("p", null, true, "unknown", List.of("unknown")));
      assertFalse(service.report("p", null, true).meeting().confirmed());
    }
  }

  @Test
  void reportsReadCurrentDependenciesWithoutModifyingLedgerOrSchedulingAnything() {
    var t =
        ledger.create(
            "p",
            "wait",
            "依存待ち",
            SecretaryTask.Intent.TODO,
            null,
            2,
            List.of(),
            List.of("dependency:dep"));
    ledger.transition("p", t.id(), t.revision(), SecretaryTask.Status.WAITING);
    var beans = new DefaultListableBeanFactory();
    beans.registerSingleton(
        "dependencies",
        (SecretaryDependencies)
            (p, id) -> Optional.of(new SecretaryDependencies.Fact("COMPLETED", 2)));
    try (var service = service()) {
      service.setDependencies(beans.getBeanProvider(SecretaryDependencies.class));
      var report = service.report("p", null, false);
      assertEquals("PENDING", report.meeting().sections().get("priorities").getFirst().state());
      assertEquals(SecretaryTask.Status.WAITING, ledger.get("p", t.id()).status());
    }
  }

  @Test
  void failedStorageIsNeverReportedAsConfirmation() {
    var failing =
        new SecretaryDatabase() {
          public <T> T read(SecretaryDatabase.Work<T> operation) {
            throw new IllegalStateException("storage offline");
          }

          public <T> T transaction(SecretaryDatabase.Work<T> operation) {
            throw new IllegalStateException("storage offline");
          }
        };
    try (var service = new SecretaryFollowups(failing, ledger, clock, n -> {}, false)) {
      assertThrows(
          IllegalStateException.class,
          () -> service.confirmMeeting("p", null, true, "offline", List.of()));
    }
  }

  @Test
  void hundredsOfSavedItemsAcrossProjectsRemainStructuredButHumanMeetingStaysBounded() {
    var beans = new DefaultListableBeanFactory();
    beans.registerSingleton(
        "saved",
        (SecretaryContext)
            (p, d, z) ->
                java.util.stream.IntStream.range(0, 210)
                    .mapToObj(
                        i ->
                            new SecretaryContext.Item(
                                "work-context",
                                "item" + i,
                                "レビュー " + i,
                                "PENDING",
                                "saved",
                                SecretaryContext.Reliability.PROPOSAL,
                                List.of("work-evidence:" + i),
                                new SecretaryContext.Observation(
                                    "run:" + i,
                                    clock.instant(),
                                    "UNKNOWN",
                                    false,
                                    "続きを確認",
                                    null,
                                    "PENDING")))
                    .toList());
    try (var service = service()) {
      service.setContexts(beans.getBeanProvider(SecretaryContext.class));
      var p = service.report("p", null, true);
      var q = service.report("q", null, true);
      assertEquals(420, p.context().size() + q.context().size());
      var global =
          new SecretaryGlobalBriefings.Report(
              p.date(),
              p.zone(),
              true,
              java.util.Map.of("p", p, "q", q),
              List.of(),
              false,
              List.of());
      assertEquals(3, global.meeting().sections().get("carryover").size());
      assertTrue(global.meeting().text().length() < 5000);
      assertTrue(ledger.list("p", 0, 100).isEmpty());
      assertEquals(
          420, global.projects().values().stream().mapToInt(v -> v.context().size()).sum());
    }
  }

  @Test
  void globalSelectionConfirmsAcrossProjectsOnceAndFlowsIntoTheNextMorning() {
    var a = task("global");
    var beans = new DefaultListableBeanFactory();
    try (var followups = service()) {
      beans.registerSingleton("reports", followups);
      var global =
          new SecretaryGlobalBriefings(
              db,
              beans.getBeanProvider(SecretaryReports.class),
              clock,
              new GlobalBriefingProperties(false, "", "", "Asia/Tokyo", List.of("p", "q")));
      String id = "p::secretary-task:" + a.id();
      global.confirmMeeting(null, true, "global", List.of(id));
      assertTrue(global.report(null, true).meeting().confirmed());
      assertNull(global.report(null, true).meeting().question());
      var morning = global.report(LocalDate.of(2026, 11, 1), false);
      assertEquals(1, morning.meeting().sections().get("handoff").size());
      assertEquals("p", morning.meeting().sections().get("handoff").getFirst().projectId());
      assertThrows(
          IllegalArgumentException.class,
          () ->
              global.confirmMeeting(null, false, "other", List.of("q::secretary-task:" + a.id())));
    }
  }

  @Test
  void globalDecisionDoesNotConfirmIndependentOrNewProjectsAndEmptyDecisionStaysEmpty() {
    var beans = new DefaultListableBeanFactory();
    task("chosen");
    try (var followups = service()) {
      beans.registerSingleton("reports", followups);
      var global =
          new SecretaryGlobalBriefings(
              db,
              beans.getBeanProvider(SecretaryReports.class),
              clock,
              new GlobalBriefingProperties(
                  false, "", "", "Asia/Tokyo", List.of("p", "q"), List.of("q")));
      global.confirmMeeting(null, false, "finish", List.of());
      assertTrue(followups.report("p", null, false).meeting().confirmed());
      assertTrue(
          followups.report("p", null, false).meeting().sections().get("priorities").isEmpty());
      assertFalse(followups.report("q", null, false).meeting().confirmed());
      assertFalse(followups.report("new", null, false).meeting().confirmed());
    }
  }

  @Test
  void absentRunIdsNeverMergeIndependentSavedWork() {
    var repository =
        new dev.mikoto2000.rei.workcontext.WorkContextRepository(
            new org.springframework.jdbc.datasource.DriverManagerDataSource(
                "jdbc:sqlite:" + root.resolve("work.db")));
    var items =
        java.util.stream.Stream.of("one", "two")
            .map(
                id ->
                    new dev.mikoto2000.rei.workcontext.WorkContext.Item(
                        id,
                        dev.mikoto2000.rei.workcontext.WorkContext.Kind.PENDING,
                        "別々の作業",
                        "saved",
                        dev.mikoto2000.rei.workcontext.WorkContext.Status.OPEN,
                        List.of(
                            new dev.mikoto2000.rei.workcontext.WorkContext.Evidence(
                                id,
                                dev.mikoto2000.rei.workcontext.WorkContext.Origin.USER,
                                null,
                                null,
                                "",
                                null,
                                null,
                                null,
                                clock.instant(),
                                clock.instant(),
                                "registered")),
                        clock.instant(),
                        clock.instant(),
                        false,
                        null))
            .toList();
    repository.save(
        new dev.mikoto2000.rei.workcontext.WorkContext(
            "p", 1, clock.instant(), clock.instant(), null, items, java.util.Set.of()),
        0);
    var context =
        new dev.mikoto2000.rei.workcontext.SecretaryWorkContext(repository)
            .context("p", LocalDate.of(2026, 10, 31), clock.getZone());
    var view =
        SecretaryMeeting.project(
            "p",
            LocalDate.of(2026, 10, 31),
            clock.getZone(),
            true,
            java.util.Map.of(),
            context,
            false,
            List.of(),
            List.of());
    assertEquals(2, view.sections().get("unfinished").size());
  }

  @Test
  void agendaEntriesRemainReferencesAndCannotBeConfirmedAsWork() {
    var beans = new DefaultListableBeanFactory();
    beans.registerSingleton(
        "agenda",
        (SecretaryContext)
            (p, d, z) ->
                List.of(
                    new SecretaryContext.Item(
                        "scheduler",
                        "scheduled",
                        "登録済み予定",
                        "PENDING",
                        "registered",
                        SecretaryContext.Reliability.FACT,
                        List.of(),
                        new SecretaryContext.Observation(
                            "scheduler:scheduled", null, "PENDING", true, null, d, "SCHEDULE"))));
    try (var service = service()) {
      service.setContexts(beans.getBeanProvider(SecretaryContext.class));
      assertFalse(service.report("p", null, false).meeting().sections().get("schedule").isEmpty());
      assertThrows(
          IllegalArgumentException.class,
          () -> service.confirmMeeting("p", null, false, "agenda", List.of("scheduler:scheduled")));
    }
  }
}
