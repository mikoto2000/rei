package dev.mikoto2000.rei.secretary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.mikoto2000.rei.core.policy.ToolApprovalRepository;
import dev.mikoto2000.rei.storage.SqliteSecretaryDatabase;
import dev.mikoto2000.rei.storage.StorageMigrationCoordinator;
import dev.mikoto2000.rei.web.ApiExceptionHandler;
import dev.mikoto2000.rei.web.SecretaryOrchestratorController;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@Tag("integration")
class SecretaryOrchestratorIntegrationTest {
  @Test
  void shellRequiresExplicitCurrentRevisionForFeedback() throws Exception {
    var db = db();
    var ledger = new SecretaryLedger(db, clock);
    task(ledger);
    try (var followups = new SecretaryFollowups(db, ledger, clock, n -> {}, false);
        var service =
            new SecretaryOrchestrator(
                db, ledger, SecretaryOrchestratorTest.reports(followups), clock, false)) {
      service.configure(settings());
      var proposal = service.evaluate("p").getFirst();
      var beans = new StaticListableBeanFactory();
      beans.addBean("ledger", ledger);
      beans.addBean("orchestrator", service);
      var secretary = new SecretaryCommand(beans.getBeanProvider(SecretaryLedger.class));
      secretary.setOrchestrators(beans.getBeanProvider(SecretaryOrchestrator.class));
      var command = new picocli.CommandLine(secretary);
      command.setOut(new java.io.PrintWriter(new java.io.StringWriter()));
      command.setErr(new java.io.PrintWriter(new java.io.StringWriter()));
      assertEquals(
          2, command.execute("feedback", proposal.id(), "--project", "p", "--reaction", "EXECUTE"));
      assertEquals("PROPOSED", service.suggestion("p", proposal.id()).status());
      assertEquals(
          0,
          command.execute(
              "feedback",
              proposal.id(),
              "--project",
              "p",
              "--revision",
              "0",
              "--reaction",
              "EXECUTE"));
      assertEquals("EXECUTION_REQUESTED", service.suggestion("p", proposal.id()).status());
    }
  }

  @TempDir Path root;
  final Clock clock = Clock.fixed(Instant.parse("2026-10-26T00:00:00Z"), ZoneOffset.UTC);

  SecretaryDatabase db() throws Exception {
    try (var migration = new StorageMigrationCoordinator(root)) {
      migration.prepare();
    }
    return new SqliteSecretaryDatabase(root.toString());
  }

  SecretaryTask task(SecretaryLedger ledger) {
    return ledger.create(
        "p", "one", "prepare release", SecretaryTask.Intent.TODO, null, 2, List.of(), List.of());
  }

  SecretaryOrchestrator.Settings settings() {
    return new SecretaryOrchestrator.Settings("p", true, false, null, 60, 3600);
  }

  @Test
  void autoFeedbackUsesExistingExpiringApprovalAndObsoleteNotificationsAreSuppressed()
      throws Exception {
    var db = db();
    var ledger = new SecretaryLedger(db, clock);
    var task = task(ledger);
    var approvals =
        new ToolApprovalRepository(
            new DriverManagerDataSource("jdbc:sqlite:" + root.resolve("approvals.db")), clock);
    var beans = new StaticListableBeanFactory();
    beans.addBean("permissions", approvals);
    try (var followups =
            new SecretaryFollowups(
                db, ledger, clock, n -> fail("Unapproved or obsolete proposal"), false);
        var service =
            new SecretaryOrchestrator(
                db, ledger, SecretaryOrchestratorTest.reports(followups), clock, false)) {
      service.configure(settings());
      followups.configure(
          new SecretaryFollowups.Settings("p", true, true, "", "", "UTC", 86400, 3, "INBOX"));
      service.setPermissions(beans.getBeanProvider(SecretaryPermissions.class));
      followups.setInterruption(service);
      var suggestion = service.evaluate("p").getFirst();
      var result =
          service.feedback("p", suggestion.id(), 0, SecretaryOrchestrator.Feedback.ALLOW_AUTO, 0);
      assertEquals("APPROVAL_REQUESTED", result.status());
      assertEquals("PENDING", approvals.get("p", result.approvalId()).status());
      assertTrue(approvals.list("other").isEmpty());
      assertEquals("secretaryAuto:IMPORTANT", approvals.get("p", result.approvalId()).tool());
      approvals.decide("p", result.approvalId(), true);
      assertEquals("APPROVED", approvals.get("p", result.approvalId()).status());
      assertEquals(SecretaryTask.Status.OPEN, ledger.get("p", task.id()).status());
      followups.checkNow("p");
      assertEquals("SUPPRESSED", followups.history("p", 10).getFirst().status());
    }
  }

  @Test
  void concurrentEvaluatorsAndAdmissionSerializeWithoutDuplicateSuggestionsOrBursts()
      throws Exception {
    var db = db();
    var ledger = new SecretaryLedger(db, clock);
    task(ledger);
    try (var followups = new SecretaryFollowups(db, ledger, clock, n -> {}, false);
        var a =
            new SecretaryOrchestrator(
                db, ledger, SecretaryOrchestratorTest.reports(followups), clock, false);
        var b =
            new SecretaryOrchestrator(
                db, ledger, SecretaryOrchestratorTest.reports(followups), clock, false);
        var workers = Executors.newFixedThreadPool(2)) {
      a.configure(settings());
      var evaluations =
          workers.invokeAll(
              List.<Callable<Integer>>of(
                  () -> a.evaluate("p").size(), () -> b.evaluate("p").size()));
      assertEquals(
          1,
          evaluations.get(0).get(5, TimeUnit.SECONDS)
              + evaluations.get(1).get(5, TimeUnit.SECONDS));
      var suggestion = a.suggestions("p", 100).getFirst();
      assertEquals(1, a.history("p", suggestion.id(), 100).size());
      var notice =
          new SecretaryNotifications.Notice(
              "n", "p", null, "BRIEFING", "brief", 3, "INBOX", clock.instant());
      var admissions =
          workers.invokeAll(
              List.<Callable<SecretaryInterruptions.Decision>>of(
                  () -> a.admit(notice).decision(), () -> b.admit(notice).decision()));
      assertEquals(
          1,
          admissions.stream()
              .map(
                  f -> {
                    try {
                      return f.get(5, TimeUnit.SECONDS);
                    } catch (Exception e) {
                      throw new AssertionError(e);
                    }
                  })
              .filter(d -> d == SecretaryInterruptions.Decision.ALLOW)
              .count());
    }
  }

  @Test
  void httpControlsAreStrictProjectScopedAndCannotGrantToolPermissions() throws Exception {
    var db = db();
    var ledger = new SecretaryLedger(db, clock);
    task(ledger);
    try (var followups = new SecretaryFollowups(db, ledger, clock, n -> {}, false);
        var service =
            new SecretaryOrchestrator(
                db, ledger, SecretaryOrchestratorTest.reports(followups), clock, false)) {
      var beans = new StaticListableBeanFactory();
      beans.addBean("orchestrator", service);
      var mvc =
          MockMvcBuilders.standaloneSetup(
                  new SecretaryOrchestratorController(
                      beans.getBeanProvider(SecretaryOrchestrator.class)))
              .setControllerAdvice(new ApiExceptionHandler())
              .build();
      mvc.perform(
              put("/api/v1/secretary/orchestrator/settings")
                  .contentType("application/json")
                  .content("{\"projectId\":\"p\",\"enabled\":true}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.minimumSeconds").value(900));
      mvc.perform(
              put("/api/v1/secretary/orchestrator/settings")
                  .contentType("application/json")
                  .content("{\"projectId\":\"p\",\"autoApprove\":true}"))
          .andExpect(status().isBadRequest());
      var proposal = service.evaluate("p").getFirst();
      String path = "/api/v1/secretary/suggestions/" + proposal.id();
      mvc.perform(get(path).param("projectId", "other")).andExpect(status().isBadRequest());
      mvc.perform(
              post(path + "/feedback")
                  .contentType("application/json")
                  .content("{\"projectId\":\"p\",\"reaction\":\"EXECUTE\"}"))
          .andExpect(status().isBadRequest());
      mvc.perform(
              post(path + "/feedback")
                  .contentType("application/json")
                  .content("{\"projectId\":\"p\",\"revision\":0,\"reaction\":\"DISMISS\"}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.status").value("DISMISSED"));
      mvc.perform(
              post(path + "/feedback")
                  .contentType("application/json")
                  .content("{\"projectId\":\"p\",\"revision\":0,\"reaction\":\"EXECUTE\"}"))
          .andExpect(status().isConflict());
    }
  }
}
