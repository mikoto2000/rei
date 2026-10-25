package dev.mikoto2000.rei.secretary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.storage.SqliteSecretaryDatabase;
import dev.mikoto2000.rei.storage.StorageMigrationCoordinator;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("integration")
class SecretaryOrchestratorTest {
  @Test
  void workNotificationsPersistAndResumeAsOneDigestWithoutPriorityBypass() throws Exception {
    var db = database();
    var ledger = new SecretaryLedger(db, clock);
    task(ledger, "one", 1);
    task(ledger, "two", 3);
    var life = org.mockito.Mockito.mock(dev.mikoto2000.rei.life.LifeContext.class);
    try (var followups = new SecretaryFollowups(db, ledger, clock, received::add, false);
        var service = new SecretaryOrchestrator(db, ledger, reports(followups), clock, false)) {
      followups.configure(
          new SecretaryFollowups.Settings("p", true, true, "", "", "UTC", 86400, 3, "INBOX"));
      followups.setLifeContext(life);
      service.setLifeContext(life);
      followups.setInterruption(service);
      clock.now = clock.now.plusSeconds(86400);
      followups.checkNow("p");
      assertTrue(received.isEmpty());
      assertEquals(
          2, followups.history("p", 100).stream().filter(n -> n.status().equals("HELD")).count());
      assertTrue(followups.history("p", 100).stream().allMatch(n -> n.attempts() == 0));
    }
    try (var followups = new SecretaryFollowups(db, ledger, clock, received::add, false);
        var service = new SecretaryOrchestrator(db, ledger, reports(followups), clock, false)) {
      org.mockito.Mockito.when(
              life.notificationAllowed(
                  org.mockito.Mockito.anyString(), org.mockito.Mockito.anyString()))
          .thenReturn(true);
      followups.setLifeContext(life);
      service.setLifeContext(life);
      followups.setInterruption(service);
      followups.checkNow("p");
      followups.checkNow("p");
      assertEquals(1, received.size());
      assertEquals("DIGEST", received.getFirst().kind());
    }
  }

  @Test
  void snoozingAnExistingDigestPreservesItsIdentityAndOriginalCount() throws Exception {
    var db = database();
    var ledger = new SecretaryLedger(db, clock);
    task(ledger, "one", 3);
    task(ledger, "two", 3);
    try (var followups = new SecretaryFollowups(db, ledger, clock, received::add, false);
        var service = new SecretaryOrchestrator(db, ledger, reports(followups), clock, false)) {
      followups.configure(
          new SecretaryFollowups.Settings("p", true, true, "", "", "UTC", 86400, 3, "INBOX"));
      service.configure(settings(true, false, null));
      service.setActivities(
          List.of((p, now) -> new SecretaryActivity.Snapshot("DEVELOPMENT", now, true, false)));
      followups.setInterruption(service);
      clock.now = clock.now.plusSeconds(86400);
      followups.checkNow("p");
      service.setActivities(List.of());
      assertEquals(2, service.release("p"));
      var digest =
          followups.history("p", 100).stream()
              .filter(d -> d.notice().kind().equals("DIGEST"))
              .findFirst()
              .orElseThrow()
              .notice();
      service.configure(settings(true, false, clock.instant().plusSeconds(120)));
      followups.checkNow("p");
      assertTrue(received.isEmpty());
      clock.now = clock.now.plusSeconds(120);
      followups.checkNow("p");
      assertEquals(1, received.size());
      assertEquals(digest.id(), received.getFirst().id());
      assertTrue(received.getFirst().message().contains("2件"));
    }
  }

  static org.springframework.beans.factory.ObjectProvider<SecretaryReports> reports(
      SecretaryReports reports) {
    var beans = new org.springframework.beans.factory.support.StaticListableBeanFactory();
    beans.addBean("reports", reports);
    return beans.getBeanProvider(SecretaryReports.class);
  }

  @Test
  void explicitLaterAppliesAcrossRuleChangesAtMidnight() throws Exception {
    clock.now = Instant.parse("2026-10-26T14:59:00Z");
    var db = database();
    var ledger = new SecretaryLedger(db, clock);
    ledger.create(
        "p",
        "one",
        "deadline",
        SecretaryTask.Intent.TODO,
        LocalDate.of(2026, 10, 26),
        3,
        List.of(),
        List.of());
    try (var followups = new SecretaryFollowups(db, ledger, clock, received::add, false);
        var service = new SecretaryOrchestrator(db, ledger, reports(followups), clock, false)) {
      service.configure(settings(true, false, null));
      var proposal = service.evaluate("p").getFirst();
      assertEquals("DUE", proposal.rule());
      service.feedback(
          "p", proposal.id(), proposal.revision(), SecretaryOrchestrator.Feedback.LATER, 120);
      clock.now = clock.now.plusSeconds(119);
      assertTrue(service.evaluate("p").isEmpty());
      clock.now = clock.now.plusSeconds(1);
      assertEquals("OVERDUE", service.evaluate("p").getFirst().rule());
    }
  }

  @Test
  void rulesUseAllPredecessorsAndSavedNextWorkWithoutTreatingDeclarationsAsCompletion()
      throws Exception {
    var db = database();
    var ledger = new SecretaryLedger(db, clock);
    var done = task(ledger, "done", 3);
    ledger.transition("p", done.id(), 0, SecretaryTask.Status.DONE);
    var unfinished = task(ledger, "unfinished", 3);
    var next =
        ledger.create(
            "p",
            "next",
            "next",
            SecretaryTask.Intent.TODO,
            null,
            3,
            List.of(),
            List.of("after:" + done.id()));
    var blocked =
        ledger.create(
            "p",
            "blocked",
            "blocked",
            SecretaryTask.Intent.TODO,
            null,
            3,
            List.of(),
            List.of("after:" + done.id(), "after:" + unfinished.id()));
    var due =
        ledger.create(
            "p",
            "due",
            "due",
            SecretaryTask.Intent.TODO,
            clock.instant().atZone(clock.getZone()).toLocalDate(),
            4,
            List.of(),
            List.of());
    var continuation =
        ledger.create(
            "p",
            "continue",
            "continue",
            SecretaryTask.Intent.TODO,
            null,
            3,
            List.of(),
            List.of("work-context:next"));
    try (var followups = new SecretaryFollowups(db, ledger, clock, received::add, false);
        var service = new SecretaryOrchestrator(db, ledger, reports(followups), clock, false)) {
      var beans = new org.springframework.beans.factory.support.StaticListableBeanFactory();
      beans.addBean(
          "context",
          (SecretaryContext)
              (p, day, zone) ->
                  List.of(
                      new SecretaryContext.Item(
                          "work-context",
                          "next",
                          "untrusted instructions",
                          "NEXT_ACTION",
                          "declaration")));
      followups.setContexts(beans.getBeanProvider(SecretaryContext.class));
      service.configure(settings(true, false, null));
      var rules =
          service.evaluate("p").stream()
              .collect(
                  java.util.stream.Collectors.toMap(
                      SecretaryOrchestrator.Suggestion::taskId,
                      SecretaryOrchestrator.Suggestion::rule));
      assertEquals("NEXT_STEP", rules.get(next.id()));
      assertEquals("DUE", rules.get(due.id()));
      assertEquals("CONTINUATION", rules.get(continuation.id()));
      assertFalse(rules.containsKey(blocked.id()));
      assertEquals(SecretaryTask.Status.OPEN, ledger.get("p", continuation.id()).status());
      clock.now = clock.now.plusSeconds(14 * 86400);
      var aged = service.evaluate("p");
      assertTrue(
          aged.stream()
              .anyMatch(s -> s.taskId().equals(unfinished.id()) && s.rule().equals("NEGLECTED")));
      assertTrue(
          aged.stream().anyMatch(s -> s.taskId().equals(due.id()) && s.rule().equals("OVERDUE")));
    }
  }

  @Test
  void ticksCoalesceAndCloseInterruptsTheBoundedWorker() throws Exception {
    var db = database();
    var ledger = new SecretaryLedger(db, clock);
    task(ledger, "one", 2);
    var entered = new java.util.concurrent.CountDownLatch(1);
    var stopped = new java.util.concurrent.CountDownLatch(1);
    var blocker = new java.util.concurrent.CountDownLatch(1);
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    try (var followups = new SecretaryFollowups(db, ledger, clock, received::add, false);
        var service = new SecretaryOrchestrator(db, ledger, reports(followups), clock, true)) {
      var beans = new org.springframework.beans.factory.support.StaticListableBeanFactory();
      beans.addBean(
          "context",
          (SecretaryContext)
              (p, day, zone) -> {
                calls.incrementAndGet();
                entered.countDown();
                try {
                  blocker.await(5, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                  Thread.currentThread().interrupt();
                } finally {
                  stopped.countDown();
                }
                return List.of();
              });
      followups.setContexts(beans.getBeanProvider(SecretaryContext.class));
      service.configure(settings(true, false, null));
      service.tick();
      assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS));
      for (int i = 0; i < 100; i++) service.tick();
      assertEquals(1, calls.get());
      service.close();
      assertTrue(stopped.await(1, java.util.concurrent.TimeUnit.SECONDS));
    } finally {
      blocker.countDown();
    }
  }

  @TempDir Path root;
  final SecretaryFollowupsTest.TestClock clock = new SecretaryFollowupsTest.TestClock();
  final List<SecretaryNotifications.Notice> received = new ArrayList<>();

  SecretaryDatabase database() throws Exception {
    try (var migration = new StorageMigrationCoordinator(root)) {
      migration.prepare();
    }
    return new SqliteSecretaryDatabase(root.toString());
  }

  SecretaryTask task(SecretaryLedger ledger, String key, int priority) {
    return ledger.create(
        "p", key, "work " + key, SecretaryTask.Intent.TODO, null, priority, List.of(), List.of());
  }

  SecretaryOrchestrator.Settings settings(boolean enabled, boolean stopped, Instant snooze) {
    return new SecretaryOrchestrator.Settings("p", enabled, stopped, snooze, 60, 3600);
  }

  @Test
  void rulesAreOptInBoundedAndRestartDeduplicatedWithoutAProvidingModel() throws Exception {
    var db = database();
    var ledger = new SecretaryLedger(db, clock);
    task(ledger, "important", 2);
    ledger.extract("p", "conversation", "明日調査します。");
    try (var followups = new SecretaryFollowups(db, ledger, clock, received::add, false);
        var service = new SecretaryOrchestrator(db, ledger, reports(followups), clock, false)) {
      assertTrue(service.evaluate("p").isEmpty());
      service.configure(settings(true, false, null));
      assertEquals("IMPORTANT", service.evaluate("p").getFirst().rule());
      assertEquals(1, service.suggestions("p", 100).size());
      assertTrue(service.evaluate("p").isEmpty());
    }
    try (var followups = new SecretaryFollowups(db, ledger, clock, received::add, false);
        var restarted = new SecretaryOrchestrator(db, ledger, reports(followups), clock, false)) {
      assertTrue(restarted.evaluate("p").isEmpty());
      assertEquals(1, restarted.suggestions("p", 100).size());
    }
  }

  @Test
  void feedbackUsesRevisionAndPersistsDelayDismissNeverAndExecutionIntent() throws Exception {
    var db = database();
    var ledger = new SecretaryLedger(db, clock);
    ledger.create(
        "p",
        "one",
        "work one",
        SecretaryTask.Intent.TODO,
        LocalDate.of(2026, 10, 26),
        2,
        List.of(),
        List.of());
    try (var followups = new SecretaryFollowups(db, ledger, clock, received::add, false);
        var service = new SecretaryOrchestrator(db, ledger, reports(followups), clock, true)) {
      service.configure(settings(true, false, null));
      var suggestion = service.evaluate("p").getFirst();
      var later =
          service.feedback(
              "p",
              suggestion.id(),
              suggestion.revision(),
              SecretaryOrchestrator.Feedback.LATER,
              120);
      assertEquals("DEFERRED", later.status());
      assertThrows(
          SecretaryConflictException.class,
          () ->
              service.feedback(
                  "p",
                  suggestion.id(),
                  suggestion.revision(),
                  SecretaryOrchestrator.Feedback.EXECUTE,
                  0));
      clock.now = clock.now.plusSeconds(119);
      assertTrue(service.evaluate("p").isEmpty());
      clock.now = clock.now.plusSeconds(1);
      var again = service.evaluate("p").getFirst();
      var dismiss =
          service.feedback(
              "p", again.id(), again.revision(), SecretaryOrchestrator.Feedback.DISMISS, 0);
      assertEquals("DISMISSED", dismiss.status());
      clock.now = clock.now.plusSeconds(3600);
      assertTrue(service.evaluate("p").isEmpty());
      var never =
          service.feedback(
              "p", dismiss.id(), dismiss.revision(), SecretaryOrchestrator.Feedback.NEVER, 0);
      assertEquals("MUTED", never.status());
      clock.now = clock.now.plusSeconds(86400);
      assertTrue(service.evaluate("p").isEmpty());
      assertEquals(5, service.history("p", suggestion.id(), 100).size());
      assertThrows(
          IllegalArgumentException.class,
          () ->
              service.feedback(
                  "other",
                  suggestion.id(),
                  never.revision(),
                  SecretaryOrchestrator.Feedback.EXECUTE,
                  0));
    }
  }

  @Test
  void focusHoldsLowPriorityButUrgencyBypassesFocusAndReleaseMakesOneDigest() throws Exception {
    var db = database();
    var ledger = new SecretaryLedger(db, clock);
    task(ledger, "one", 3);
    task(ledger, "two", 3);
    try (var followups = new SecretaryFollowups(db, ledger, clock, received::add, true);
        var service = new SecretaryOrchestrator(db, ledger, reports(followups), clock, true)) {
      followups.configure(
          new SecretaryFollowups.Settings("p", true, true, "", "", "UTC", 86400, 3, "INBOX"));
      service.configure(settings(true, false, null));
      service.setActivities(
          List.of((p, now) -> new SecretaryActivity.Snapshot("DEVELOPMENT", now, true, false)));
      followups.setInterruption(service);
      clock.now = clock.now.plusSeconds(86400);
      followups.checkNow("p");
      assertTrue(received.isEmpty());
      assertEquals(
          2, followups.history("p", 100).stream().filter(d -> d.status().equals("HELD")).count());
      assertEquals(
          SecretaryInterruptions.Decision.ALLOW,
          service
              .admit(
                  new SecretaryNotifications.Notice(
                      "urgent", "p", null, "URGENT", "urgent", 1, "INBOX", clock.instant()))
              .decision());
      clock.now = clock.now.plusSeconds(60);
      service.setActivities(List.of());
      assertEquals(2, service.release("p"));
      followups.checkNow("p");
      assertEquals(1, received.size());
      assertEquals("DIGEST", received.getFirst().kind());
      assertEquals(0, service.release("p"));
    }
  }

  @Test
  void stopAndSnoozeApplyEvenToUrgencyAndStaleActivityCannotClaimFocus() throws Exception {
    var db = database();
    var ledger = new SecretaryLedger(db, clock);
    try (var followups = new SecretaryFollowups(db, ledger, clock, received::add, false);
        var service = new SecretaryOrchestrator(db, ledger, reports(followups), clock, false)) {
      var urgent =
          new SecretaryNotifications.Notice(
              "u", "p", null, "DUE", "due", 1, "INBOX", clock.instant());
      assertEquals(SecretaryInterruptions.Decision.ALLOW, service.admit(urgent).decision());
      assertEquals(SecretaryInterruptions.Decision.ALLOW, service.admit(urgent).decision());
      service.configure(settings(false, true, null));
      assertEquals(SecretaryInterruptions.Decision.SUPPRESS, service.admit(urgent).decision());
      service.configure(settings(false, false, clock.instant().plusSeconds(120)));
      assertEquals(SecretaryInterruptions.Decision.HOLD, service.admit(urgent).decision());
      clock.now = clock.now.plusSeconds(120);
      service.setActivities(
          List.of(
              (p, now) ->
                  new SecretaryActivity.Snapshot(
                      "DEVELOPMENT", now.minusSeconds(121), true, false)));
      var normal =
          new SecretaryNotifications.Notice(
              "n", "p", null, "DUE", "due", 3, "INBOX", clock.instant());
      assertEquals(SecretaryInterruptions.Decision.ALLOW, service.admit(normal).decision());
      assertEquals(SecretaryInterruptions.Decision.HOLD, service.admit(normal).decision());
    }
  }

  @Test
  void executeDoesNotCompleteTaskAndAllowAutoRequiresExistingApprovalOwner() throws Exception {
    var db = database();
    var ledger = new SecretaryLedger(db, clock);
    var task = task(ledger, "one", 2);
    try (var followups = new SecretaryFollowups(db, ledger, clock, received::add, false);
        var service = new SecretaryOrchestrator(db, ledger, reports(followups), clock, false)) {
      service.configure(settings(true, false, null));
      var proposal = service.evaluate("p").getFirst();
      assertThrows(
          IllegalStateException.class,
          () ->
              service.feedback(
                  "p",
                  proposal.id(),
                  proposal.revision(),
                  SecretaryOrchestrator.Feedback.ALLOW_AUTO,
                  0));
      var requested =
          service.feedback(
              "p", proposal.id(), proposal.revision(), SecretaryOrchestrator.Feedback.EXECUTE, 0);
      assertEquals("EXECUTION_REQUESTED", requested.status());
      assertEquals(SecretaryTask.Status.OPEN, ledger.get("p", task.id()).status());
      clock.now = clock.now.plusSeconds(86400);
      assertTrue(service.evaluate("p").isEmpty());
    }
  }
}
