package dev.mikoto2000.rei.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.application.run.ChatSubmitService;
import dev.mikoto2000.rei.application.run.RunRegistry;
import dev.mikoto2000.rei.application.run.RunService;
import dev.mikoto2000.rei.application.run.RunStatus;
import dev.mikoto2000.rei.application.run.SessionRegistry;
import dev.mikoto2000.rei.application.session.SessionLifecycle;
import dev.mikoto2000.rei.conversation.ConversationAdmissionStore;
import dev.mikoto2000.rei.conversation.ConversationTurnStore;
import dev.mikoto2000.rei.conversation.SqliteConversationTurnStore;
import dev.mikoto2000.rei.conversation.SqliteSessionRepository;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.project.ProjectContext;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.storage.StorageDatabase;
import dev.mikoto2000.rei.storage.StorageMigrationCoordinator;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** MCPツール一覧の結果と状態の契約を検証するテスト。 */
class McpToolsTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** 検証に使う実行可否の判断。 */
  StorageMigrationCoordinator gate;

  /** 検証に使う受付台帳。 */
  ConversationAdmissionStore admissions;

  /** 検証に使う保存先。 */
  McpRequestStore store;

  /** 検証に使うツール一覧。 */
  McpTools tools;

  /** 検証に使うプロジェクト一覧。 */
  ProjectRegistry projects;

  /** 検証に使うプロジェクト、その他。 */
  ProjectContext project, other;

  /** 検証に使うやり取り一覧。 */
  ConversationTurnStore turns;

  /** 検証に使うchats。 */
  ChatSubmitService chats;

  /** 日時や期限の条件を再現するために使う検証時刻。 */
  final Clock clock = Clock.fixed(Instant.parse("2026-10-12T00:00:00Z"), ZoneOffset.UTC);

  /** 各検証を独立した状態で始められるよう、依存先と検証データを準備する。 */
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

  /** 次の検証へ状態を持ち越さないよう、利用した資源と保存先を片付ける。 */
  @AfterEach
  void close() throws Exception {
    admissions.close();
    gate.close();
  }

  /** {@code data} の条件を再現するための補助情報を返す。 */
  @SuppressWarnings("unchecked")
  Map<String, Object> data(McpSchema.CallToolResult result) {
    return (Map<String, Object>) result.structuredContent();
  }

  /** {@code input} の条件を再現するための補助情報を返す。 */
  Map<String, Object> input(String key) {
    return Map.of("project_id", project.id(), "instruction", "Review only", "request_id", key);
  }

  /** {@code error} の条件を再現するため、検証用の操作や状態を扱う。 */
  void error(String code, McpSchema.CallToolResult result) {
    assertTrue(result.isError());
    assertEquals(code, ((Map<?, ?>) data(result).get("error")).get("code"));
  }

  /** MCPによる機能の提供で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void onlyAllowlistedProjectNamesAreExposed() {
    var result = tools.call("rei_projects_list", Map.of());
    assertFalse(result.isError());
    assertEquals(1, ((List<?>) data(result).get("projects")).size());
    assertFalse(data(result).toString().contains(root.toString()));
  }

  /** MCPによる機能の提供で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
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

  /** MCPによる機能の提供で、指定した条件に対する結果と状態の変化を確認する。 */
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

  /** MCPによる機能の提供の操作が、指定された権限・承認条件に従うことを確認する。 */
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

  /** MCPによる機能の提供で、条件が変わっても、引き継ぐべき情報や状態を保持することを確認する。 */
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

  /** MCPによる機能の提供で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void restartDoesNotRedispatchAndKeepsReceipt() {
    String run = (String) data(tools.call("rei_task_submit", input("key"))).get("run_id");
    admissions.close();
    try (var restored = new ConversationAdmissionStore(root, clock)) {
      assertEquals(RunStatus.UNKNOWN, restored.get(run).orElseThrow().status());
      assertTrue(store.byRequest(ConversationAdmissionStore.hash("key")).isPresent());
    }
  }

  /** MCPによる機能の提供で、時刻や期限に応じて、待機・実行・保存の扱いを切り替えることを確認する。 */
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

  /** MCPによる機能の提供で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void cursorBindsProjectRunAndSnapshot() {
    var codec = new McpCursor(store.cursorKey());
    String cursor = codec.encode("p", "r", "snapshot", 3);
    assertEquals(3, codec.decode(cursor, "p", "r", "snapshot"));
    assertThrows(McpError.class, () -> codec.decode(cursor, "other", "r", "snapshot"));
    assertThrows(McpError.class, () -> codec.decode(cursor, "p", "other", "snapshot"));
    assertThrows(McpError.class, () -> codec.decode(cursor, "p", "r", "changed"));
  }

  /** MCPによる機能の提供で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
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

  /** MCPによる機能の提供で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
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

  /** MCPによる機能の提供で、指定した条件に対する結果と状態の変化を確認する。 */
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

  /** MCPによる機能の提供で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
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

  /** MCPによる機能の提供の操作が、指定された権限・承認条件に従うことを確認する。 */
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

  /** MCPによる機能の提供で、競合する要求でも、実行順序と作業の分離を守ることを確認する。 */
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

  /** MCPによる機能の提供で、時刻や期限に応じて、待機・実行・保存の扱いを切り替えることを確認する。 */
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

  /** MCPによる機能の提供の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
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

  /** MCPによる機能の提供で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
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
