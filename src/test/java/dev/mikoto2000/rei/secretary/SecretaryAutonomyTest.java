package dev.mikoto2000.rei.secretary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.storage.SqliteSecretaryDatabase;
import dev.mikoto2000.rei.storage.StorageMigrationCoordinator;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

@Tag("integration")
class SecretaryAutonomyTest {
  @Test
  void runningPreparationIsPausedWhenTheWorkScopeIsRevoked() throws Exception {
    var db = db();
    var ledger = new SecretaryLedger(db, clock);
    var task = task(ledger);
    var life = org.mockito.Mockito.mock(dev.mikoto2000.rei.life.LifePolicy.class);
    org.mockito.Mockito.when(life.backgroundAllowed("p", task.id())).thenReturn(true);
    try (var service = service(db, ledger)) {
      service.setLifeContext(life);
      service.configure(settings(1, 3, 1));
      var job = service.create(request(task, "life-change", SecretaryAutonomy.Kind.RESEARCH, null));
      service.start("p", job.id(), job.revision());
      org.mockito.Mockito.when(life.backgroundAllowed("p", task.id())).thenReturn(false);
      assertFalse(runtime.budget.tryReserve());
      service.check();
      assertEquals("PAUSED", service.get("p", job.id()).state());
      assertEquals(1, runtime.cancels);
    }
  }

  @Test
  void changedTaskCannotReserveAnotherModelCallOrSaveAnOldResult() throws Exception {
    var db = db();
    var ledger = new SecretaryLedger(db, clock);
    var task = task(ledger);
    try (var service = service(db, ledger)) {
      service.configure(settings(1, 3, 1));
      var job =
          service.create(request(task, "source-change", SecretaryAutonomy.Kind.RESEARCH, null));
      service.start("p", job.id(), job.revision());
      ledger.transition("p", task.id(), task.revision(), SecretaryTask.Status.DONE);
      assertFalse(runtime.budget.tryReserve());
      runtime.completed.accept(new SecretaryPreparationRuntime.Outcome("SUCCESS", "obsolete", ""));
      assertEquals("BLOCKED", service.get("p", job.id()).state());
      assertEquals(null, service.get("p", job.id()).result());
    }
  }

  @Test
  void externalTasksNeverUseGenericPlannerAndConsentIsRevocableBeforeBudgetReservation()
      throws Exception {
    var db = db();
    var ledger = new SecretaryLedger(db, clock);
    var candidate =
        ledger.externalCandidate("p", "external:one", "private subject", null, "external:c:item");
    var task =
        ledger.update(
            "p",
            candidate.id(),
            candidate.revision(),
            candidate.title(),
            candidate.intent(),
            SecretaryTask.Status.OPEN,
            null,
            3,
            candidate.tags(),
            candidate.references());
    try (var service = service(db, ledger)) {
      service.configure(settings(2, 3, 1));
      assertThrows(
          IllegalStateException.class,
          () -> service.create(request(task, "blocked", SecretaryAutonomy.Kind.SUMMARY, null)));
      assertEquals(0, runtime.starts);
      var allowed = new java.util.concurrent.atomic.AtomicBoolean(true);
      var beans = new StaticListableBeanFactory();
      beans.addBean(
          "external",
          new SecretaryPreparationContext() {
            public Evidence prepareExternal(SecretaryTask ignored) {
              return new Evidence("sanitized evidence", "permission", "model");
            }

            public boolean allowsExternal(SecretaryTask ignored, String permission) {
              return allowed.get() && permission.equals("permission");
            }
          });
      service.setExternalContexts(beans.getBeanProvider(SecretaryPreparationContext.class));
      var job = service.create(request(task, "explicit", SecretaryAutonomy.Kind.SUMMARY, null));
      service.start("p", job.id(), job.revision());
      assertEquals(1, runtime.starts);
      allowed.set(false);
      assertFalse(runtime.budget.tryReserve());
      assertEquals(0, service.get("p", job.id()).calls());
      assertFalse(
          service
              .context(
                  "p",
                  clock.instant().atZone(java.time.ZoneOffset.UTC).toLocalDate(),
                  java.time.ZoneOffset.UTC)
              .toString()
              .contains("sanitized evidence"));
    }
  }

  @Test
  void interruptedAdmissionCannotTurnAnAttemptCounterIntoAnApproval() throws Exception {
    var db = db();
    var ledger = new SecretaryLedger(db, clock);
    var task = task(ledger);
    try (var service = service(db, ledger)) {
      service.configure(settings(2, 3, 1));
      var job =
          service.create(
              request(
                  task, "one", SecretaryAutonomy.Kind.UPDATE_TASK, SecretaryTask.Status.WAITING));
      db.transaction(
          connection -> {
            try (var q =
                connection.prepareStatement(
                    "UPDATE secretary_jobs SET state='PAUSED',attempts=1 WHERE id=?")) {
              q.setString(1, job.id());
              q.executeUpdate();
            }
            return null;
          });
      var result = service.resume("p", job.id(), service.get("p", job.id()).revision());
      assertEquals("WAITING_APPROVAL", result.state());
      assertEquals(SecretaryTask.Status.OPEN, ledger.get("p", task.id()).status());
      assertEquals(0, permits.consumed);
    }
  }

  @Test
  void taskMutationCannotCommitAfterCancellationWasConfirmed() throws Exception {
    var original = db();
    var boundary = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    var interposed = new java.util.concurrent.atomic.AtomicBoolean();
    SecretaryDatabase wrapped =
        new SecretaryDatabase() {
          public <T> T read(Work<T> work) {
            return original.read(work);
          }

          public <T> T transaction(Work<T> work) {
            T value = original.transaction(work);
            if (value instanceof SecretaryAutonomy.Job job && job.state().equals("FINALIZING")) {
              interposed.set(true);
              boundary.countDown();
              try {
                assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS));
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
              }
            } else if (value instanceof SecretaryAutonomy.Job job
                && job.state().equals("COMPLETED")) boundary.countDown();
            return value;
          }
        };
    var ledger = new SecretaryLedger(wrapped, clock);
    var task = task(ledger);
    try (var service = service(wrapped, ledger);
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
      service.configure(settings(2, 3, 1));
      permits.approved = true;
      var job =
          service.create(
              request(
                  task, "one", SecretaryAutonomy.Kind.UPDATE_TASK, SecretaryTask.Status.WAITING));
      var future = executor.submit(() -> service.start("p", job.id(), job.revision()));
      assertTrue(boundary.await(4, java.util.concurrent.TimeUnit.SECONDS));
      if (interposed.get()) service.cancel("p", job.id(), service.get("p", job.id()).revision());
      release.countDown();
      future.get(5, java.util.concurrent.TimeUnit.SECONDS);
      var result = service.get("p", job.id());
      if (result.state().equals("CANCELLED"))
        assertEquals(SecretaryTask.Status.OPEN, ledger.get("p", task.id()).status());
      else {
        assertEquals("COMPLETED", result.state());
        assertEquals(SecretaryTask.Status.WAITING, ledger.get("p", task.id()).status());
      }
    } finally {
      release.countDown();
    }
  }

  @TempDir Path root;
  final SecretaryFollowupsTest.TestClock clock = new SecretaryFollowupsTest.TestClock();
  final FakeRuntime runtime = new FakeRuntime();
  final FakePermissions permits = new FakePermissions();
  final List<SecretaryNotifications.Notice> notifications = new ArrayList<>();
  boolean policyEnabled = true;
  String policyDecision = "REQUIRE_APPROVAL";

  final class FakeRuntime implements SecretaryPreparationRuntime {
    boolean available = true;
    int starts;
    int cancels;
    String state = "RUNNING";
    CallBudget budget;
    Consumer<Outcome> completed;

    @Override
    public boolean available() {
      return available;
    }

    @Override
    public void validate(String project, String session) {
      if (!project.equals("p") || !session.equals("s"))
        throw new IllegalArgumentException("Owner mismatch");
    }

    @Override
    public void start(Request request, CallBudget budget, Consumer<Outcome> completed) {
      starts++;
      this.budget = budget;
      this.completed = completed;
      state = "RUNNING";
    }

    @Override
    public Snapshot inspect(String project, String session, String run) {
      return new Snapshot(state, state.equals("RUNNING"));
    }

    @Override
    public void cancel(String project, String session, String run) {
      cancels++;
      state = "CANCELLED";
    }
  }

  static final class FakePermissions implements SecretaryPermissions {
    boolean approved;
    int consumed;

    @Override
    public String requestSecretaryPermission(String project, String task, String kind) {
      return "prep-permit";
    }

    @Override
    public String requestSecretaryAction(String project, String action, String canonical) {
      return "permit";
    }

    @Override
    public String secretaryActionStatus(
        String project, String id, String action, String canonical) {
      return approved ? "APPROVED" : "PENDING";
    }

    @Override
    public boolean consumeSecretaryAction(
        String project, String id, String action, String canonical) {
      if (!approved) return false;
      consumed++;
      approved = false;
      return true;
    }
  }

  SecretaryDatabase db() throws Exception {
    try (var migration = new StorageMigrationCoordinator(root)) {
      migration.prepare();
    }
    return new SqliteSecretaryDatabase(root.toString());
  }

  SecretaryGoalRuntime goalSource;

  SecretaryAutonomy service(SecretaryDatabase db, SecretaryLedger ledger) {
    var beans = new StaticListableBeanFactory();
    beans.addBean("runtime", runtime);
    if (goalSource != null) beans.addBean("goals", goalSource);
    beans.addBean("permissions", permits);
    beans.addBean(
        "policy",
        (SecretaryExecutionPolicy)
            kind ->
                new SecretaryExecutionPolicy.Decision(policyEnabled, policyDecision, "policy-1"));
    var service = new SecretaryAutonomy(db, ledger, clock, false);
    service.setServices(
        beans.getBeanProvider(SecretaryPreparationRuntime.class),
        beans.getBeanProvider(SecretaryExecutionPolicy.class),
        beans.getBeanProvider(SecretaryPermissions.class),
        beans.getBeanProvider(SecretaryDrafts.class),
        beans.getBeanProvider(SecretaryGoalRuntime.class),
        beans.getBeanProvider(SecretaryOrchestrator.class),
        beans.getBeanProvider(SecretaryReports.class));
    return service;
  }

  SecretaryAutonomy.Settings settings(int level, int calls, int retries) {
    return new SecretaryAutonomy.Settings(
        "p", true, "s", level, false, SecretaryAutonomy.Kind.PREPARE, 1, 300, calls, 10, retries);
  }

  SecretaryTask task(SecretaryLedger ledger) {
    return ledger.create(
        "p", "one", "research release", SecretaryTask.Intent.TODO, null, 2, List.of(), List.of());
  }

  SecretaryAutonomy.Request request(
      SecretaryTask task, String key, SecretaryAutonomy.Kind kind, SecretaryTask.Status target) {
    return new SecretaryAutonomy.Request("p", task.id(), task.revision(), key, kind, target, null);
  }

  @Test
  void offAndUnavailablePolicyNeverDispatchAndOwnershipCannotCrossProjects() throws Exception {
    var db = db();
    var ledger = new SecretaryLedger(db, clock);
    var task = task(ledger);
    try (var service = service(db, ledger)) {
      assertThrows(
          IllegalStateException.class,
          () -> service.create(request(task, "one", SecretaryAutonomy.Kind.RESEARCH, null)));
      service.configure(settings(1, 3, 1));
      policyEnabled = false;
      var job = service.create(request(task, "one", SecretaryAutonomy.Kind.RESEARCH, null));
      var blocked = service.start("p", job.id(), job.revision());
      assertEquals("BLOCKED", blocked.state());
      assertEquals(SecretaryExecutionMode.PROPOSAL_ONLY, service.executionMode("p", job.id()));
      assertEquals(0, runtime.starts);
      assertThrows(IllegalArgumentException.class, () -> service.get("other", job.id()));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              service.configure(
                  new SecretaryAutonomy.Settings(
                      "other",
                      true,
                      "s",
                      1,
                      false,
                      SecretaryAutonomy.Kind.PREPARE,
                      1,
                      300,
                      3,
                      10,
                      1)));
    }
  }

  @Test
  void reservationsPrecedeCallsAndCompletionMeansPreparedNotTaskDone() throws Exception {
    var db = db();
    var ledger = new SecretaryLedger(db, clock);
    var task = task(ledger);
    try (var service = service(db, ledger)) {
      service.configure(settings(1, 2, 1));
      var job = service.create(request(task, "one", SecretaryAutonomy.Kind.SUMMARY, null));
      assertEquals(SecretaryExecutionMode.AUTO_PREPARATION, service.executionMode("p", job.id()));
      service.start("p", job.id(), job.revision());
      assertTrue(runtime.budget.tryReserve());
      assertTrue(runtime.budget.tryReserve());
      assertFalse(runtime.budget.tryReserve());
      assertEquals(2, service.get("p", job.id()).calls());
      runtime.state = "COMPLETED";
      runtime.completed.accept(new SecretaryPreparationRuntime.Outcome("SUCCESS", "summary", ""));
      var prepared = service.get("p", job.id());
      assertEquals("PREPARED", prepared.state());
      assertEquals("summary", prepared.result().text());
      assertEquals(
          "summary",
          service
              .context("p", clock.instant().atZone(clock.getZone()).toLocalDate(), clock.getZone())
              .getFirst()
              .reason());
      assertEquals(64, prepared.result().sha256().length());
      assertEquals(SecretaryTask.Status.OPEN, ledger.get("p", task.id()).status());
      assertEquals(
          job.id(),
          service.create(request(task, "one", SecretaryAutonomy.Kind.SUMMARY, null)).id());
      assertThrows(
          SecretaryConflictException.class,
          () -> service.create(request(task, "one", SecretaryAutonomy.Kind.STATUS, null)));
    }
  }

  @Test
  void internalTaskUpdateWaitsForExactApprovalAndVerifiesItsOwnLedgerReference() throws Exception {
    var db = db();
    var ledger = new SecretaryLedger(db, clock);
    var task = task(ledger);
    try (var service = service(db, ledger)) {
      service.configure(settings(2, 3, 1));
      var job =
          service.create(
              request(
                  task, "one", SecretaryAutonomy.Kind.UPDATE_TASK, SecretaryTask.Status.WAITING));
      assertEquals("WAITING_APPROVAL", job.state());
      assertEquals(SecretaryExecutionMode.APPROVAL_REQUIRED, service.executionMode("p", job.id()));
      assertEquals(SecretaryTask.Status.OPEN, ledger.get("p", task.id()).status());
      assertEquals("WAITING_APPROVAL", service.start("p", job.id(), job.revision()).state());
      permits.approved = true;
      var completed = service.start("p", job.id(), service.get("p", job.id()).revision());
      assertEquals("COMPLETED", completed.state());
      assertEquals(SecretaryExecutionMode.APPROVED_SCOPE, service.executionMode("p", job.id()));
      assertEquals(1, permits.consumed);
      assertEquals(0, runtime.starts);
      var changed = ledger.get("p", task.id());
      assertEquals(SecretaryTask.Status.WAITING, changed.status());
      assertTrue(changed.references().contains("secretary-execution:" + job.id()));
      assertEquals("COMPLETED", service.start("p", job.id(), completed.revision()).state());
      assertEquals(1, permits.consumed);
    }
  }

  @Test
  void cancellationAndSuspendResumeKeepCallAndAttemptBudgets() throws Exception {
    var db = db();
    var ledger = new SecretaryLedger(db, clock);
    var task = task(ledger);
    try (var service = service(db, ledger)) {
      service.configure(settings(1, 3, 1));
      var job = service.create(request(task, "one", SecretaryAutonomy.Kind.PREPARE, null));
      service.start("p", job.id(), job.revision());
      assertTrue(runtime.budget.tryReserve());
      service.suspend("p", job.id(), service.get("p", job.id()).revision());
      service.check();
      var paused = service.get("p", job.id());
      assertEquals("PAUSED", paused.state());
      assertEquals(1, runtime.cancels);
      service.resume("p", job.id(), paused.revision());
      assertEquals(2, runtime.starts);
      assertEquals(2, runtime.budget.remaining());
      assertTrue(runtime.budget.tryReserve());
      assertTrue(runtime.budget.tryReserve());
      assertFalse(runtime.budget.tryReserve());
      service.cancel("p", job.id(), service.get("p", job.id()).revision());
      service.check();
      assertEquals("CANCELLED", service.get("p", job.id()).state());
      assertEquals(3, service.get("p", job.id()).calls());
    }
  }

  @Test
  void unknownRunAfterRestartIsNotAutomaticallyRepeatedAndDeadlinesCancelRealOwner()
      throws Exception {
    var db = db();
    var ledger = new SecretaryLedger(db, clock);
    var task = task(ledger);
    String id;
    try (var service = service(db, ledger)) {
      service.configure(settings(1, 3, 1));
      var job = service.create(request(task, "one", SecretaryAutonomy.Kind.RESEARCH, null));
      id = job.id();
      service.start("p", id, job.revision());
      assertTrue(runtime.budget.tryReserve());
    }
    runtime.state = "UNKNOWN";
    try (var restarted = service(db, ledger)) {
      restarted.check();
      assertEquals("UNCERTAIN", restarted.get("p", id).state());
      restarted.check();
      assertEquals(1, runtime.starts);
      assertEquals(1, restarted.get("p", id).calls());
      assertThrows(
          IllegalStateException.class,
          () -> restarted.start("p", id, restarted.get("p", id).revision()));
      var second = restarted.create(request(task, "two", SecretaryAutonomy.Kind.RESEARCH, null));
      restarted.start("p", second.id(), second.revision());
      clock.now = clock.now.plusSeconds(301);
      restarted.check();
      assertTrue(runtime.cancels > 0);
      assertFalse(runtime.budget.tryReserve());
      assertEquals("TIMED_OUT", restarted.get("p", second.id()).state());
    }
  }

  @Test
  void retryBudgetAndConcurrencyPreventDuplicateOrUnboundedWork() throws Exception {
    var db = db();
    var ledger = new SecretaryLedger(db, clock);
    var task = task(ledger);
    try (var service = service(db, ledger)) {
      service.configure(settings(1, 3, 1));
      var one = service.create(request(task, "one", SecretaryAutonomy.Kind.RESEARCH, null));
      var other =
          ledger.create(
              "p", "other", "other", SecretaryTask.Intent.TODO, null, 2, List.of(), List.of());
      var two = service.create(request(other, "two", SecretaryAutonomy.Kind.RESEARCH, null));
      service.start("p", one.id(), one.revision());
      assertTrue(runtime.budget.tryReserve());
      assertEquals("READY", service.start("p", two.id(), two.revision()).state());
      assertEquals(1, runtime.starts);
      runtime.state = "FAILED";
      runtime.completed.accept(new SecretaryPreparationRuntime.Outcome("FAILED", "", "transient"));
      clock.now = clock.now.plusSeconds(60);
      service.check();
      assertEquals(2, runtime.starts);
      assertTrue(runtime.budget.tryReserve());
      runtime.state = "FAILED";
      runtime.completed.accept(new SecretaryPreparationRuntime.Outcome("FAILED", "", "transient"));
      clock.now = clock.now.plusSeconds(60);
      service.check();
      assertEquals("FAILED", service.get("p", one.id()).state());
      assertEquals(2, service.get("p", one.id()).attempts());
    }
  }

  @Test
  void executionApiAndShellRequireRevisionAndRetainProjectOwnership() throws Exception {
    var db = db();
    var ledger = new SecretaryLedger(db, clock);
    var task = task(ledger);
    try (var service = service(db, ledger)) {
      service.configure(settings(1, 3, 1));
      var beans = new StaticListableBeanFactory();
      beans.addBean("autonomy", service);
      beans.addBean("ledger", ledger);
      var mvc =
          org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                  new dev.mikoto2000.rei.web.SecretaryAutonomyController(
                      beans.getBeanProvider(SecretaryAutonomy.class)))
              .setControllerAdvice(new dev.mikoto2000.rei.web.ApiExceptionHandler())
              .build();
      mvc.perform(
              org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                      "/api/v1/secretary/executions")
                  .contentType("application/json")
                  .content(
                      "{\"projectId\":\"p\",\"taskId\":\""
                          + task.id()
                          + "\",\"key\":\"api\",\"kind\":\"PREPARE\"}"))
          .andExpect(
              org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                  .isBadRequest());
      var job = service.create(request(task, "api", SecretaryAutonomy.Kind.PREPARE, null));
      String path = "/api/v1/secretary/executions/" + job.id();
      mvc.perform(
              org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                      path + "/mode")
                  .param("projectId", "p"))
          .andExpect(
              org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
          .andExpect(
              org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.mode")
                  .value("AUTO_PREPARATION"));
      mvc.perform(
              org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)
                  .param("projectId", "other"))
          .andExpect(
              org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                  .isBadRequest());
      mvc.perform(
              org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                      path + "/cancel")
                  .contentType("application/json")
                  .content("{\"projectId\":\"p\",\"revision\":99}"))
          .andExpect(
              org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                  .isConflict());
      var command = new SecretaryCommand(beans.getBeanProvider(SecretaryLedger.class));
      command.setAutonomies(beans.getBeanProvider(SecretaryAutonomy.class));
      var cli = new picocli.CommandLine(command);
      cli.setOut(new java.io.PrintWriter(new java.io.StringWriter()));
      cli.setErr(new java.io.PrintWriter(new java.io.StringWriter()));
      assertEquals(2, cli.execute("work-start", job.id(), "--project", "p"));
      assertEquals(0, runtime.starts);
    }
  }

  @Test
  void pendingApprovalsCannotStarveAcceptedPreparationWork() throws Exception {
    var db = db();
    var ledger = new SecretaryLedger(db, clock);
    var task = task(ledger);
    try (var service = service(db, ledger)) {
      service.configure(settings(2, 3, 1));
      for (int index = 0; index < 8; index++)
        service.create(
            request(
                task,
                "waiting:" + index,
                SecretaryAutonomy.Kind.UPDATE_TASK,
                SecretaryTask.Status.WAITING));
      var next =
          ledger.create(
              "p",
              "two",
              "research next",
              SecretaryTask.Intent.TODO,
              null,
              2,
              List.of(),
              List.of());
      var ready = service.create(request(next, "ready", SecretaryAutonomy.Kind.PREPARE, null));
      service.check();
      assertEquals(1, runtime.starts);
      assertEquals("RUNNING", service.get("p", ready.id()).state());
      assertTrue(
          service.list("p", 20).stream()
                  .filter(
                      j ->
                          j.state().equals("WAITING_APPROVAL")
                              && j.notBefore().isAfter(clock.instant()))
                  .count()
              >= 7);
    }
  }

  @Test
  void interruptedWritableGoalRequiresEffectInspectionBeforeResume() throws Exception {
    var state = new java.util.concurrent.atomic.AtomicReference<>("READY");
    var definition = new java.util.concurrent.atomic.AtomicReference<>("definition");
    var verified = new java.util.concurrent.atomic.AtomicBoolean();
    goalSource =
        new SecretaryGoalRuntime() {
          public Goal describe(String project, String id) {
            return new Goal(
                id,
                "s",
                definition.get(),
                state.get(),
                1,
                2,
                0,
                1,
                "goal-root",
                verified.get(),
                "");
          }

          public void start(String project, String id, String fingerprint) {
            state.set("RUNNING");
            runtime.state = "RUNNING";
          }

          public void stop(String project, String id, boolean cancel) {
            state.set(cancel ? "CANCELLED" : "PAUSED");
            runtime.state = "CANCELLED";
          }
        };
    var db = db();
    var ledger = new SecretaryLedger(db, clock);
    var task = task(ledger);
    try (var service = service(db, ledger)) {
      service.configure(settings(3, 3, 1));
      permits.approved = true;
      runtime.available = false;
      var blocked =
          service.create(
              new SecretaryAutonomy.Request(
                  "p",
                  task.id(),
                  task.revision(),
                  "unavailable-goal",
                  SecretaryAutonomy.Kind.GOAL,
                  null,
                  "goal-one"));
      assertEquals("BLOCKED", service.start("p", blocked.id(), blocked.revision()).state());
      assertEquals("READY", state.get());
      runtime.available = true;
      var job =
          service.create(
              new SecretaryAutonomy.Request(
                  "p",
                  task.id(),
                  task.revision(),
                  "goal",
                  SecretaryAutonomy.Kind.GOAL,
                  null,
                  "goal-one"));
      var running = service.start("p", job.id(), job.revision());
      assertEquals("RUNNING", running.state());
      var interrupted = service.suspend("p", job.id(), running.revision());
      assertEquals("UNCERTAIN", interrupted.state());
      assertEquals("goal_interruption_effects_unconfirmed", interrupted.reason());
      assertThrows(
          IllegalStateException.class, () -> service.resume("p", job.id(), interrupted.revision()));
      assertEquals(2, service.status("p").callsReservedToday());
      definition.set("weakened-definition");
      verified.set(true);
      state.set("COMPLETED");
      assertEquals("UNCERTAIN", service.verify("p", job.id(), interrupted.revision()).state());
    }
  }
}
