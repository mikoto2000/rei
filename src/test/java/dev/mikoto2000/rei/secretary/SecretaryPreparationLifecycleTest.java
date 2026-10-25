package dev.mikoto2000.rei.secretary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.storage.SqliteSecretaryDatabase;
import dev.mikoto2000.rei.storage.StorageMigrationCoordinator;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("integration")
class SecretaryPreparationLifecycleTest {
  @Test
  void aNewSourceRevisionCanReplaceTheInvalidatedProposalImmediately() throws Exception {
    var db = database();
    var ledger = new SecretaryLedger(db, clock);
    var task = task(ledger, "one");
    try (var reports = new SecretaryFollowups(db, ledger, clock, n -> {}, false);
        var service = service(db, ledger, reports)) {
      var first = service.evaluate("p").getFirst();
      var changed =
          ledger.update(
              "p",
              task.id(),
              task.revision(),
              "changed preparation",
              task.intent(),
              task.status(),
              task.due(),
              task.priority(),
              task.tags(),
              task.references());
      var next = service.evaluate("p").getFirst();
      assertEquals(changed.revision(), next.taskRevision());
      assertTrue(next.revision() > first.revision());
    }
  }

  @TempDir Path root;
  final SecretaryFollowupsTest.TestClock clock = new SecretaryFollowupsTest.TestClock();

  SecretaryDatabase database() throws Exception {
    try (var migration = new StorageMigrationCoordinator(root)) {
      migration.prepare();
    }
    return new SqliteSecretaryDatabase(root.toString());
  }

  SecretaryTask task(SecretaryLedger ledger, String key) {
    return ledger.create(
        "p",
        key,
        key,
        SecretaryTask.Intent.TODO,
        LocalDate.of(2026, 10, 26),
        1,
        List.of("prep-minutes:30"),
        List.of());
  }

  SecretaryOrchestrator service(
      SecretaryDatabase db, SecretaryLedger ledger, SecretaryFollowups followups) {
    var value =
        new SecretaryOrchestrator(
            db, ledger, SecretaryOrchestratorTest.reports(followups), clock, false);
    value.configure(new SecretaryOrchestrator.Settings("p", true, false, null, 900, 86400));
    return value;
  }

  @Test
  void changedTaskCannotExecuteOrAcceptFeedbackFromAnOldProposal() throws Exception {
    var db = database();
    var ledger = new SecretaryLedger(db, clock);
    var task = task(ledger, "one");
    try (var reports = new SecretaryFollowups(db, ledger, clock, n -> {}, false);
        var service = service(db, ledger, reports)) {
      var proposal = service.evaluate("p").getFirst();
      ledger.transition("p", task.id(), task.revision(), SecretaryTask.Status.DONE);
      assertTrue(service.executionCandidates("p", 0, 100).isEmpty());
      assertEquals("INVALIDATED", service.suggestion("p", proposal.id()).status());
      assertThrows(
          SecretaryConflictException.class,
          () ->
              service.feedback(
                  "p",
                  proposal.id(),
                  proposal.revision(),
                  SecretaryOrchestrator.Feedback.EXECUTE,
                  0));
    }
  }

  @Test
  void expiredProposalIsNotReissuedDailyWithoutNewInformation() throws Exception {
    var db = database();
    var ledger = new SecretaryLedger(db, clock);
    task(ledger, "one");
    try (var reports = new SecretaryFollowups(db, ledger, clock, n -> {}, false);
        var service = service(db, ledger, reports)) {
      var proposal = service.evaluate("p").getFirst();
      clock.now = clock.now.plusSeconds(8 * 86400L);
      assertTrue(service.executionCandidates("p", 0, 100).isEmpty());
      assertEquals("EXPIRED", service.suggestion("p", proposal.id()).status());
      // A new overdue rule can be proposed once; the unchanged rule cannot repeat each day.
      service.evaluate("p");
      clock.now = clock.now.plusSeconds(86400);
      assertTrue(service.evaluate("p").isEmpty());
    }
  }

  @Test
  void dismissalAppliesAcrossRuleChangesAndDoesNotReturnTheNextDay() throws Exception {
    var db = database();
    var ledger = new SecretaryLedger(db, clock);
    task(ledger, "one");
    try (var reports = new SecretaryFollowups(db, ledger, clock, n -> {}, false);
        var service = service(db, ledger, reports)) {
      var proposal = service.evaluate("p").getFirst();
      service.feedback(
          "p", proposal.id(), proposal.revision(), SecretaryOrchestrator.Feedback.DISMISS, 0);
      clock.now = clock.now.plusSeconds(2 * 86400L);
      assertTrue(service.evaluate("p").isEmpty());
    }
  }

  @Test
  void activeSuggestionsAreBoundedAndAppearOnceInReportContext() throws Exception {
    var db = database();
    var ledger = new SecretaryLedger(db, clock);
    for (int i = 0; i < 10; i++) task(ledger, "task" + i);
    try (var reports = new SecretaryFollowups(db, ledger, clock, n -> {}, false);
        var service = service(db, ledger, reports)) {
      assertEquals(3, service.evaluate("p").size());
      assertTrue(service.evaluate("p").isEmpty());
      assertEquals(3, service.context("p", LocalDate.of(2026, 10, 26), clock.getZone()).size());
      assertTrue(
          service.preparation("p").stream()
              .allMatch(p -> p.mode() == SecretaryExecutionMode.PROPOSAL_ONLY));
    }
  }

  @Test
  void aThirtyDayPostponementSurvivesRestartAndIsNotExpiredAfterSevenDays() throws Exception {
    var db = database();
    var ledger = new SecretaryLedger(db, clock);
    task(ledger, "one");
    String id;
    try (var reports = new SecretaryFollowups(db, ledger, clock, n -> {}, false);
        var service = service(db, ledger, reports)) {
      var proposal = service.evaluate("p").getFirst();
      id = proposal.id();
      service.feedback(
          "p", id, proposal.revision(), SecretaryOrchestrator.Feedback.LATER, 30 * 86400L);
    }
    clock.now = clock.now.plusSeconds(8 * 86400L);
    try (var reports = new SecretaryFollowups(db, ledger, clock, n -> {}, false);
        var service = service(db, ledger, reports)) {
      assertEquals("DEFERRED", service.suggestion("p", id).status());
      assertTrue(service.evaluate("p").isEmpty());
    }
  }

  @Test
  void longRegisteredPreparationStartsBeforeTheOrdinaryDueReminderWindow() throws Exception {
    var db = database();
    var ledger = new SecretaryLedger(db, clock);
    ledger.create(
        "p",
        "long",
        "prepare",
        SecretaryTask.Intent.TODO,
        LocalDate.of(2026, 10, 29),
        3,
        List.of("prep-minutes:10080"),
        List.of());
    try (var reports = new SecretaryFollowups(db, ledger, clock, n -> {}, false);
        var service = service(db, ledger, reports)) {
      assertEquals("PREPARATION", service.evaluate("p").getFirst().rule());
    }
  }
}
