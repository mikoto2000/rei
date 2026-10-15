package dev.mikoto2000.rei.mcp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.mikoto2000.rei.application.run.*;
import dev.mikoto2000.rei.application.session.*;
import dev.mikoto2000.rei.conversation.*;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.core.project.*;
import dev.mikoto2000.rei.storage.*;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class McpToolsTest {
  @TempDir Path root;
  StorageMigrationCoordinator gate;
  ConversationAdmissionStore admissions;
  McpRequestStore store;
  McpTools tools;
  ProjectRegistry projects;
  ProjectContext project, other;
  ConversationTurnStore turns;
  ChatSubmitService chats;
  final Clock clock = Clock.fixed(Instant.parse("2026-10-12T00:00:00Z"), ZoneOffset.UTC);

  @BeforeEach
  void setup() throws Exception {
    gate = new StorageMigrationCoordinator(root);
    gate.prepare();
    admissions = new ConversationAdmissionStore(root, clock);
    turns = new SqliteConversationTurnStore(root);
    store =
        new McpRequestStore(
            root, admissions, turns, clock, Duration.ofDays(30), Duration.ofDays(90));
    projects = new ProjectRegistry(root.resolve("projects.json"));
    project = projects.resolve(root);
    other = projects.resolve(java.nio.file.Files.createDirectory(root.resolve("other")));
    var sessions = new SqliteSessionRepository(root);
    var lifecycle = new SessionLifecycle(sessions, clock);
    lifecycle.setAdmissions(admissions);
    lifecycle.onSelected(c -> fail("MCP changed selected session"));
    var registry = new RunRegistry(clock);
    registry.setAdmissions(admissions);
    chats =
        new ChatSubmitService(
            projects,
            new SessionRegistry(clock),
            registry,
            lifecycle,
            (c, p) -> assertEquals(AgentRunContext.Mode.READ_ONLY, c.mode()),
            true);
    var approvals = mock(dev.mikoto2000.rei.core.policy.ToolApprovalRepository.class);
    when(approvals.list(anyString())).thenReturn(List.of());
    tools =
        new McpTools(
            projects,
            Set.of(project.id()),
            mock(dev.mikoto2000.rei.workcontext.WorkContextService.class),
            chats,
            mock(RunService.class),
            store,
            clock,
            true,
            Duration.ofDays(90),
            new McpCursor(store.cursorKey()),
            approvals);
  }

  @AfterEach
  void close() throws Exception {
    admissions.close();
    gate.close();
  }

  @SuppressWarnings("unchecked")
  Map<String, Object> data(McpSchema.CallToolResult result) {
    return (Map<String, Object>) result.structuredContent();
  }

  Map<String, Object> input(String key) {
    return Map.of("project_id", project.id(), "instruction", "Review only", "request_id", key);
  }

  void error(String code, McpSchema.CallToolResult result) {
    assertTrue(result.isError());
    assertEquals(code, ((Map<?, ?>) data(result).get("error")).get("code"));
  }

  @Test
  void onlyAllowlistedProjectNamesAreExposed() {
    var result = tools.call("rei_projects_list", Map.of());
    assertFalse(result.isError());
    assertEquals(1, ((List<?>) data(result).get("projects")).size());
    assertFalse(data(result).toString().contains(root.toString()));
  }

  @Test
  void duplicateIsReplayedAndChangedPayloadConflicts() {
    var first = tools.call("rei_task_submit", input("key"));
    assertFalse(first.isError(), () -> data(first).toString());
    var replay = tools.call("rei_task_submit", new LinkedHashMap<>(input("key")));
    assertEquals(data(first).get("run_id"), data(replay).get("run_id"));
    assertEquals(true, data(replay).get("replayed"));
    error(
        "REQUEST_CONFLICT",
        tools.call(
            "rei_task_submit",
            Map.of("project_id", project.id(), "instruction", "different", "request_id", "key")));
  }

  @Test
  void foreignRunAndShellSessionAreHidden() {
    var first = tools.call("rei_task_submit", input("key"));
    String run = (String) data(first).get("run_id");
    error(
        "NOT_FOUND",
        tools.call("rei_task_status", Map.of("project_id", other.id(), "run_id", run)));
    error(
        "NOT_FOUND",
        tools.call("rei_task_cancel", Map.of("project_id", other.id(), "run_id", run)));
    error(
        "NOT_FOUND",
        tools.call(
            "rei_task_submit",
            Map.of(
                "project_id",
                project.id(),
                "instruction",
                "review",
                "request_id",
                "other",
                "session_id",
                "shell-session")));
  }

  @Test
  void exactlyOneLookupKeyAndNoAuthorityEscalation() {
    error(
        "INVALID_ARGUMENT",
        tools.call(
            "rei_task_status",
            Map.of("project_id", project.id(), "run_id", "r", "request_id", "k")));
    var a = new HashMap<>(input("key"));
    a.put("mode", "EXCLUSIVE");
    error("INVALID_ARGUMENT", tools.call("rei_task_submit", a));
    error(
        "INVALID_ARGUMENT",
        tools.call(
            "rei_task_submit",
            Map.of(
                "project_id",
                project.id(),
                "instruction",
                "x".repeat(16385),
                "request_id",
                "key")));
  }

  @Test
  void statusAndImmutableResultSurviveLostEvents() {
    var first = data(tools.call("rei_task_submit", input("key")));
    String run = (String) first.get("run_id");
    var context = admissions.get(run).orElseThrow().context();
    assertEquals(
        "QUEUED",
        data(tools.call("rei_task_status", Map.of("project_id", project.id(), "request_id", "key")))
            .get("state"));
    error(
        "RESULT_NOT_READY",
        tools.call("rei_task_result", Map.of("project_id", project.id(), "run_id", run)));
    turns.start(context, "request", clock.instant());
    turns.finish(
        context, ConversationTurnStore.Status.COMPLETED, "api_key=secretvalue safe😀 output");
    admissions.transition(run, RunStatus.COMPLETED);
    var page =
        data(
            tools.call(
                "rei_task_result",
                Map.of("project_id", project.id(), "run_id", run, "max_chars", 8)));
    assertFalse(page.toString().contains("secretvalue"));
    assertNotNull(page.get("next_cursor"));
    var next =
        tools.call(
            "rei_task_result",
            Map.of("project_id", project.id(), "run_id", run, "cursor", page.get("next_cursor")));
    assertFalse(next.isError());
    error(
        "INVALID_ARGUMENT",
        tools.call(
            "rei_task_result",
            Map.of(
                "project_id",
                project.id(),
                "run_id",
                run,
                "cursor",
                page.get("next_cursor") + "x")));
    assertEquals("COMPLETED", store.byRun(run).orElseThrow().state());
  }

  @Test
  void restartDoesNotRedispatchAndKeepsReceipt() {
    String run = (String) data(tools.call("rei_task_submit", input("key"))).get("run_id");
    admissions.close();
    try (var restored = new ConversationAdmissionStore(root, clock)) {
      assertEquals(RunStatus.UNKNOWN, restored.get(run).orElseThrow().status());
      assertTrue(store.byRequest(ConversationAdmissionStore.hash("key")).isPresent());
    }
  }

  @Test
  void expiredRequestCannotBeReused() {
    tools.call("rei_task_submit", input("key"));
    new StorageDatabase(root)
        .transaction(
            c -> {
              try (var q = c.createStatement()) {
                q.executeUpdate("UPDATE mcp_requests SET expires_at='2020-01-01T00:00:00Z'");
                return null;
              }
            });
    error("REQUEST_EXPIRED", tools.call("rei_task_submit", input("key")));
  }

  @Test
  void cursorBindsProjectRunAndSnapshot() {
    var codec = new McpCursor(store.cursorKey());
    String cursor = codec.encode("p", "r", "snapshot", 3);
    assertEquals(3, codec.decode(cursor, "p", "r", "snapshot"));
    assertThrows(McpError.class, () -> codec.decode(cursor, "other", "r", "snapshot"));
    assertThrows(McpError.class, () -> codec.decode(cursor, "p", "other", "snapshot"));
    assertThrows(McpError.class, () -> codec.decode(cursor, "p", "r", "changed"));
  }

  @Test
  void concurrentDuplicateSubmissionsAdmitOneRun() throws Exception {
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(6)) {
      var futures = new ArrayList<java.util.concurrent.Future<Object>>();
      for (int i = 0; i < 12; i++)
        futures.add(
            executor.submit(
                () -> {
                  var result = tools.call("rei_task_submit", input("parallel"));
                  assertFalse(result.isError());
                  return data(result).get("run_id");
                }));
      var ids = new HashSet<Object>();
      for (var future : futures) ids.add(future.get());
      assertEquals(1, ids.size());
    }
  }

  @Test
  void allPersistedLifecycleStatesAreReturnedWithoutInventedProgress() {
    for (var state : RunStatus.values()) {
      var first = data(tools.call("rei_task_submit", input(state.name())));
      String run = (String) first.get("run_id");
      admissions.transition(run, state);
      var status =
          data(tools.call("rei_task_status", Map.of("project_id", project.id(), "run_id", run)));
      assertEquals(state.name(), status.get("state"));
      assertNull(status.get("progress"));
    }
  }

  @Test
  void resultExpiresButKeyTombstoneRemains() {
    String run = (String) data(tools.call("rei_task_submit", input("expire-result"))).get("run_id");
    var context = admissions.get(run).orElseThrow().context();
    turns.start(context, "request", clock.instant());
    turns.finish(context, ConversationTurnStore.Status.COMPLETED, "saved");
    admissions.transition(run, RunStatus.COMPLETED);
    assertFalse(
        tools.call("rei_task_result", Map.of("project_id", project.id(), "run_id", run)).isError());
    new StorageDatabase(root)
        .transaction(
            c -> {
              try (var q = c.createStatement()) {
                q.executeUpdate("UPDATE mcp_requests SET result_expires_at='2020-01-01T00:00:00Z'");
                return null;
              }
            });
    store.expire();
    error(
        "RESULT_EXPIRED",
        tools.call("rei_task_result", Map.of("project_id", project.id(), "run_id", run)));
    assertTrue(store.byRequest(ConversationAdmissionStore.hash("expire-result")).isPresent());
  }

  @Test
  void failedAdmissionReplaysSameCapacityErrorInsteadOfAcceptedReceipt() {
    var lifecycle = new SessionLifecycle(new SqliteSessionRepository(root), clock);
    lifecycle.setAdmissions(admissions);
    var rejecting =
        new ChatSubmitService(
            projects,
            new SessionRegistry(clock),
            new RunRegistry(clock),
            lifecycle,
            (c, p) -> {
              throw new dev.mikoto2000.rei.core.chat.ProjectRunQueue.CapacityExceededException();
            },
            true);
    var local =
        new McpTools(
            projects,
            Set.of(project.id()),
            mock(dev.mikoto2000.rei.workcontext.WorkContextService.class),
            rejecting,
            mock(RunService.class),
            store,
            clock,
            true,
            Duration.ofDays(90),
            new McpCursor(store.cursorKey()),
            mock(dev.mikoto2000.rei.core.policy.ToolApprovalRepository.class));
    error("CAPACITY_EXCEEDED", local.call("rei_task_submit", input("rejected")));
    error("CAPACITY_EXCEEDED", local.call("rei_task_submit", input("rejected")));
    assertEquals(
        RunStatus.FAILED,
        store
            .status(store.byRequest(ConversationAdmissionStore.hash("rejected")).orElseThrow())
            .status());
  }

  @Test
  void approvalDoesNotRewriteFailedRunAsWaitingOrAutomaticallyResume() {
    String run = (String) data(tools.call("rei_task_submit", input("approval"))).get("run_id");
    var r = store.byRun(run).orElseThrow();
    admissions.transition(run, RunStatus.FAILED);
    var approvals = mock(dev.mikoto2000.rei.core.policy.ToolApprovalRepository.class);
    when(approvals.list(project.id()))
        .thenReturn(
            List.of(
                new dev.mikoto2000.rei.core.policy.ToolApprovalRepository.Request(
                    "approval-id",
                    project.id(),
                    r.session(),
                    run,
                    "readMultiFile",
                    "",
                    "APPROVED",
                    clock.instant().plusSeconds(100))));
    var local =
        new McpTools(
            projects,
            Set.of(project.id()),
            mock(dev.mikoto2000.rei.workcontext.WorkContextService.class),
            chats,
            mock(RunService.class),
            store,
            clock,
            true,
            Duration.ofDays(90),
            new McpCursor(store.cursorKey()),
            approvals);
    var status =
        data(local.call("rei_task_status", Map.of("project_id", project.id(), "run_id", run)));
    assertEquals("FAILED", status.get("state"));
    assertNull(status.get("waiting_reason"));
    assertEquals(
        true, ((Map<?, ?>) ((List<?>) status.get("approval")).getFirst()).get("resume_required"));
  }

  @Test
  void disabledParallelFeatureNeverFallsBackToExclusive() {
    var local =
        new McpTools(
            projects,
            Set.of(project.id()),
            mock(dev.mikoto2000.rei.workcontext.WorkContextService.class),
            chats,
            mock(RunService.class),
            store,
            clock,
            false,
            Duration.ofDays(90),
            new McpCursor(store.cursorKey()),
            mock(dev.mikoto2000.rei.core.policy.ToolApprovalRepository.class));
    error("FEATURE_DISABLED", local.call("rei_task_submit", input("disabled")));
    assertTrue(store.byRequest(ConversationAdmissionStore.hash("disabled")).isEmpty());
  }

  @Test
  void expiredTerminalTombstoneDoesNotRetainAcceptanceText() {
    var args = new HashMap<>(input("minimal-tombstone"));
    args.put("acceptance_criteria", List.of("private acceptance text"));
    String run = (String) data(tools.call("rei_task_submit", args)).get("run_id");
    var context = admissions.get(run).orElseThrow().context();
    turns.start(context, "request", clock.instant());
    turns.finish(context, ConversationTurnStore.Status.COMPLETED, "saved");
    admissions.transition(run, RunStatus.COMPLETED);
    store.snapshot(store.byRun(run).orElseThrow());
    new StorageDatabase(root)
        .transaction(
            c -> {
              try (var q = c.createStatement()) {
                q.executeUpdate(
                    "UPDATE mcp_requests SET expires_at='2020-01-01T00:00:00Z',result_expires_at='2020-01-01T00:00:00Z'");
                return null;
              }
            });
    store.expire();
    assertTrue(store.byRun(run).orElseThrow().criteria().isEmpty());
    assertTrue(store.byRequest(ConversationAdmissionStore.hash("minimal-tombstone")).isPresent());
    error("REQUEST_EXPIRED", tools.call("rei_task_submit", args));
  }

  @Test
  void twoAllowedProjectsStillCannotShareSessionRunOrCancellation() {
    String run = (String) data(tools.call("rei_task_submit", input("cross-allowed"))).get("run_id");
    var r = store.byRun(run).orElseThrow();
    var local =
        new McpTools(
            projects,
            Set.of(project.id(), other.id()),
            mock(dev.mikoto2000.rei.workcontext.WorkContextService.class),
            chats,
            mock(RunService.class),
            store,
            clock,
            true,
            Duration.ofDays(90),
            new McpCursor(store.cursorKey()),
            mock(dev.mikoto2000.rei.core.policy.ToolApprovalRepository.class));
    for (String tool : List.of("rei_task_status", "rei_task_result", "rei_task_cancel"))
      error("NOT_FOUND", local.call(tool, Map.of("project_id", other.id(), "run_id", run)));
    error(
        "NOT_FOUND",
        local.call(
            "rei_task_submit",
            Map.of(
                "project_id",
                other.id(),
                "instruction",
                "review",
                "request_id",
                "other-project",
                "session_id",
                r.session())));
  }

  @Test
  void replayReturnsExistingLogicalCheckpointTaskSeparatelyFromRunAndSession() {
    String run =
        (String) data(tools.call("rei_task_submit", input("checkpoint-replay"))).get("run_id");
    var r = store.byRun(run).orElseThrow();
    var checkpoints = mock(dev.mikoto2000.rei.checkpoint.PersistentCheckpointRepository.class);
    when(checkpoints.findByRun(project.id(), run))
        .thenReturn(
            Optional.of(
                dev.mikoto2000.rei.checkpoint.PersistentCheckpoint.initial(
                    "logical-task", project.id(), r.session(), run, root, "request")));
    store.setCheckpoints(checkpoints);
    var replay = data(tools.call("rei_task_submit", input("checkpoint-replay")));
    assertEquals("logical-task", replay.get("task_id"));
    assertEquals(run, replay.get("run_id"));
    assertEquals(r.session(), replay.get("session_id"));
  }
}
