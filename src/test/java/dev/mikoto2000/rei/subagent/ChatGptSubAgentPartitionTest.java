package dev.mikoto2000.rei.subagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyList;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.application.run.RunRegistry;
import dev.mikoto2000.rei.application.run.RunStatus;
import dev.mikoto2000.rei.chatgpt.ChatGptException;
import dev.mikoto2000.rei.chatgpt.PlanClient;
import dev.mikoto2000.rei.chatgpt.PlanExecutionProperties;
import dev.mikoto2000.rei.chatgpt.PlanProperties;
import dev.mikoto2000.rei.chatgpt.PlanSseAssembler;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.policy.ActionCapability;
import dev.mikoto2000.rei.core.policy.PermissionDecision;
import dev.mikoto2000.rei.core.policy.ToolApprovalRepository;
import dev.mikoto2000.rei.core.policy.ToolPermissionException;
import dev.mikoto2000.rei.core.policy.ToolPermissionGuard;
import dev.mikoto2000.rei.core.policy.ToolPermissionPolicy;
import dev.mikoto2000.rei.core.policy.ToolPermissionProperties;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget.LlmCallReservation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** チャットGPT補助エージェント区分の結果と状態の契約を検証するテスト。 */
class ChatGptSubAgentPartitionTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** 検証に使う保存や受け渡しに用いるデータ形式の変換。 */
  final com.fasterxml.jackson.databind.ObjectMapper json =
      new com.fasterxml.jackson.databind.ObjectMapper();

  /** 検証に使うクライアント。 */
  PlanClient client;

  /** 検証に使う利用条件の確認。 */
  ToolPermissionGuard guard;

  /** 検証に使う登録台帳。 */
  SubAgentRegistry registry;

  /** 検証に使う実行一覧。 */
  RunRegistry runs;

  /** 検証に使う所有者。 */
  AgentRunContext owner;

  /** 検証に使う予算。 */
  LlmCallReservation budget;

  /** 日時や期限の条件を再現するために使う検証時刻。 */
  Clock clock = Clock.systemUTC();

  /** 通知された出来事と順序を検証するための記録。 */
  List<AgentEvent> events;

  /** 検証に使うタスク一覧。 */
  List<String> tasks;

  /** 各検証を独立した状態で始められるよう、依存先と検証データを準備する。 */
  @BeforeEach
  void setup() throws Exception {
    Files.writeString(
        root.resolve("review.yaml"),
        """
        id: design-review
        name: Design
        description: Review
        systemPrompt: Review supplied documents
        tools: []
        maxSteps: 8
        maxRepairs: 2
        timeout: 120s
        """);
    registry =
        new SubAgentRegistry(
            root, new SubAgentDefinitionLoader(new SubAgentToolPolicy(Set.of()), model -> true));
    assertThat(registry.reload()).isEmpty();
    client = mock(PlanClient.class);
    guard = mock(ToolPermissionGuard.class);
    runs = new RunRegistry(clock);
    owner = new AgentRunContext("parent", "session", root, "project");
    runs.register(owner);
    runs.transition(owner.runId(), RunStatus.RUNNING, null);
    budget = mock(LlmCallReservation.class);
    when(budget.tryReserve()).thenReturn(true);
    events = new ArrayList<>();
    tasks = new ArrayList<>();
    doAnswer(
            call -> {
              assertThat(call.<String>getArgument(0)).isEqualTo("default");
              assertThat(call.<String>getArgument(1)).isEqualTo("model");
              ((Runnable) call.getArgument(8)).run();
              String task = call.getArgument(3);
              tasks.add(task);
              assertThat(
                      PlanClient.encodedRequestBytes(
                          "model",
                          call.getArgument(2),
                          List.of(new PlanClient.Input("user", task))))
                  .isLessThanOrEqualTo(call.getArgument(5));
              return response(task);
            })
        .when(client)
        .infer(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            any(),
            anyInt(),
            anyInt(),
            any(),
            any());
  }

  /** {@code backend} の条件を再現するための補助情報を返す。 */
  ChatGptSubAgentBackend backend(ToolPermissionGuard permission) {
    return new ChatGptSubAgentBackend(
        client,
        new PlanProperties(true, root, Set.of("design-review")),
        registry,
        permission,
        new CommandCancellationService(),
        new AgentEventFactory(clock),
        events::add,
        clock,
        runs);
  }

  /** 検証で使用する要求を、指定された条件に合わせて用意する。 */
  ChatGptSubAgentBackend.Request request(List<String> sources) {
    return new ChatGptSubAgentBackend.Request(
        "design-review",
        "chatgpt-plan",
        "model",
        "default",
        "Review",
        sources,
        "{\"decision\":\"accept\"}");
  }

  /** {@code files} の条件を再現するための補助情報を返す。 */
  List<String> files(int count, String content) throws Exception {
    var names = new ArrayList<String>();
    for (int i = 0; i < count; i++) {
      String name = "source-" + i + ".md";
      names.add(name);
      Files.writeString(root.resolve(name), content);
    }
    return names;
  }

  /** 検証で使用する応答を、指定された条件に合わせて用意する。 */
  PlanSseAssembler.Result response(String task) throws Exception {
    var input = json.readTree(task);
    var wrapper =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            json.readTree(ChatGptSubAgentBackendTest.VALID);
    wrapper.set("scope", input.path("scope"));
    var refs = wrapper.putArray("references");
    for (var source : input.path("sources")) {
      var ref = refs.addObject();
      for (String field : List.of("referenceId", "path", "sha256"))
        ref.set(field, source.get(field));
      String content = source.path("content").asText();
      ref.put("quote", content.substring(0, Math.min(content.length(), 32)));
    }
    return new PlanSseAssembler.Result(
        PlanSseAssembler.State.COMPLETED, wrapper.toString(), 17, "response", null);
  }

  /** 検証済みグループ結果一覧を維持することを確認する。 */
  @Test
  void recursiveSplitRunsSequentiallyInOriginalOrderAndKeepsValidatedGroupResults()
      throws Exception {
    var names = files(8, "Plain paragraph.\n" + "x".repeat(70000));
    var receipt = backend(guard).run(request(names), owner, false, budget);
    assertThat(receipt.state()).isEqualTo(ChatGptSubAgentBackend.State.COMPLETED);
    assertThat(receipt.groups()).hasSize(4);
    assertThat(tasks).hasSize(4);
    assertThat(receipt.groups().stream().flatMap(g -> g.scope().stream()).toList())
        .isEqualTo(names);
    assertThat(receipt.groups())
        .allSatisfy(
            g -> {
              assertThat(g.state()).isEqualTo(ChatGptSubAgentBackend.State.COMPLETED);
              assertThat(g.result().subAgentRunId()).isEqualTo(receipt.requestId());
              assertThat(g.result().structuredOutput()).isNotNull();
              assertThat(g.totalTokens()).isEqualTo(17);
            });
    assertThat(receipt.totalTokens()).isEqualTo(68);
    assertThat(receipt.result().structuredOutput()).isNull();
    var host = json.readTree(receipt.result().output());
    assertThat(host.path("groups").size()).isEqualTo(4);
    assertThat(host.path("remainingScopes").isEmpty()).isTrue();
    assertThat(host.path("unverified").toString()).contains("Cross-group");
    verify(guard, times(1)).check(eq("requestChatGptPlanSubAgent"), anyString(), eq(owner));
    verify(budget, times(4)).tryReserve();
    verify(budget, times(4)).recordTotalTokens(17);
    for (String name : names) assertThat(Files.readString(root.resolve(name))).hasSize(70017);
    var comparison =
        PlanReviewComparison.compare(
            List.of(
                new ParallelSubAgentDelegator.Item(
                    "review",
                    "design-review",
                    ParallelSubAgentDelegator.Status.COMPLETED,
                    receipt.result(),
                    receipt)));
    assertThat(comparison.truthVerified()).isFalse();
    assertThat(comparison.evidence().get("review")).hasSize(8);
  }

  /** 補助エージェントへの委譲で、入力や保存形式を契約に従って解釈し、情報を受け渡すことを確認する。 */
  @Test
  void doubleEncodedQuotesTriggerSplittingEvenWhenRawTotalFits() throws Exception {
    var names = files(2, "\"".repeat(45000));
    var receipt = backend(guard).run(request(names), owner, false, budget);
    assertThat(receipt.state()).isEqualTo(ChatGptSubAgentBackend.State.COMPLETED);
    assertThat(tasks).hasSize(2);
  }

  /** 補助エージェントへの委譲で、時刻や期限に応じて、待機・実行・保存の扱いを切り替えることを確認する。 */
  @Test
  void thirtyMinuteDeadlineIsSharedAcrossGroupsWithoutResettingParentAccounting() throws Exception {
    var definition = root.resolve("review.yaml");
    Files.writeString(
        definition, Files.readString(definition).replace("timeout: 120s", "timeout: 30m"));
    assertThat(registry.reload()).isEmpty();
    var remaining = new ArrayList<Duration>();
    doAnswer(
            call -> {
              remaining.add(call.getArgument(4));
              ((Runnable) call.getArgument(8)).run();
              String task = call.getArgument(3);
              tasks.add(task);
              Thread.sleep(20);
              return response(task);
            })
        .when(client)
        .infer(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            any(),
            anyInt(),
            anyInt(),
            any(),
            any());
    var service = backend(guard);
    service.configureExecution(
        new PlanExecutionProperties(
            false, null, null, null, null, null, Duration.ofMinutes(30), false));
    var receipt = service.run(request(files(8, "x".repeat(70000))), owner, false, budget);
    assertThat(receipt.state()).isEqualTo(ChatGptSubAgentBackend.State.COMPLETED);
    assertThat(remaining).hasSize(4);
    assertThat(remaining.getFirst())
        .isGreaterThan(Duration.ofMinutes(29))
        .isLessThanOrEqualTo(Duration.ofMinutes(30));
    for (int i = 1; i < remaining.size(); i++)
      assertThat(remaining.get(i)).isLessThan(remaining.get(i - 1).minusMillis(15));
    verify(budget, times(4)).tryReserve();
    verify(budget, times(4)).recordTotalTokens(17);
    assertThat(receipt.totalTokens()).isEqualTo(68);
  }

  /** 最初結果を保持することを確認する。 */
  @Test
  void failureInTheSecondGroupStopsThirdAndRetainsFirstResult() throws Exception {
    var names = files(8, "x".repeat(70000));
    var count = new AtomicInteger();
    doAnswer(
            call -> {
              ((Runnable) call.getArgument(8)).run();
              String task = call.getArgument(3);
              tasks.add(task);
              if (count.incrementAndGet() == 2)
                return new PlanSseAssembler.Result(
                    PlanSseAssembler.State.FAILED,
                    "",
                    17,
                    "response",
                    ChatGptException.Code.USAGE_LIMIT);
              return response(task);
            })
        .when(client)
        .infer(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            any(),
            anyInt(),
            anyInt(),
            any(),
            any());
    var receipt = backend(guard).run(request(names), owner, false, budget);
    assertThat(tasks).hasSize(2);
    assertThat(receipt.state()).isEqualTo(ChatGptSubAgentBackend.State.FAILED);
    assertThat(receipt.groups()).hasSize(2);
    assertThat(receipt.groups().getFirst().result().structuredOutput()).isNotNull();
    assertThat(receipt.groups().getLast().error()).isEqualTo(ChatGptException.Code.USAGE_LIMIT);
    assertThat(json.readTree(receipt.result().output()).path("remainingScopes").size())
        .isEqualTo(2);
  }

  /** 補助エージェントへの委譲で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void exhaustedSharedBudgetDoesNotStartTheNextGroup() throws Exception {
    when(budget.tryReserve()).thenReturn(true, false);
    var receipt = backend(guard).run(request(files(4, "x".repeat(70000))), owner, false, budget);
    assertThat(tasks).hasSize(1);
    assertThat(receipt.groups().getFirst().state())
        .isEqualTo(ChatGptSubAgentBackend.State.COMPLETED);
    assertThat(receipt.result().output()).contains("SHARED_LLM_CALL_BUDGET_EXHAUSTED");
  }

  /** 補助エージェントへの委譲で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void policyRevokedBetweenGroupsStopsBeforeAnotherInference() throws Exception {
    doNothing()
        .doThrow(new ToolPermissionException("requestChatGptPlanSubAgent", PermissionDecision.DENY))
        .when(guard)
        .checkAuthorizedPlanPolicy(anyString(), anyString(), eq(owner));
    var receipt = backend(guard).run(request(files(4, "x".repeat(70000))), owner, false, budget);
    assertThat(tasks).hasSize(1);
    assertThat(receipt.groups().getFirst().state())
        .isEqualTo(ChatGptSubAgentBackend.State.COMPLETED);
    assertThat(receipt.error()).isEqualTo(ChatGptException.Code.PERMISSION_DENIED);
    assertThat(receipt.result().output()).contains("TOOL_PERMISSION_DENIED");
  }

  /** 補助エージェントへの委譲で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void ambiguousSecondDispatchStopsWithoutRetryingOrStartingLaterGroups() throws Exception {
    var count = new AtomicInteger();
    doAnswer(
            call -> {
              ((Runnable) call.getArgument(8)).run();
              String task = call.getArgument(3);
              tasks.add(task);
              if (count.incrementAndGet() == 2)
                throw ChatGptException.of(ChatGptException.Code.TRANSPORT);
              return response(task);
            })
        .when(client)
        .infer(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            any(),
            anyInt(),
            anyInt(),
            any(),
            any());
    var receipt = backend(guard).run(request(files(8, "x".repeat(70000))), owner, false, budget);
    assertThat(tasks).hasSize(2);
    assertThat(receipt.state()).isEqualTo(ChatGptSubAgentBackend.State.OUTCOME_UNKNOWN);
    assertThat(receipt.totalTokens()).isNull();
    assertThat(receipt.groups().getFirst().totalTokens()).isEqualTo(17);
    assertThat(receipt.groups().getLast().state())
        .isEqualTo(ChatGptSubAgentBackend.State.OUTCOME_UNKNOWN);
  }

  /** 補助エージェントへの委譲の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void plannedGroupsExceedingConfiguredIterationsAreRejectedBeforeApprovalOrDispatch()
      throws Exception {
    var backend = backend(guard);
    backend.configureExecution(
        new PlanExecutionProperties(
            true, 1, 0, 1, 262144, 1048576, Duration.ofSeconds(120), false));
    var receipt = backend.run(request(files(4, "x".repeat(70000))), owner, false, budget);
    assertThat(receipt.result().output()).contains("GROUP_CALL_BUDGET_EXCEEDED");
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(client);
    verify(guard, never()).check(eq("requestChatGptPlanSubAgent"), anyString(), eq(owner));
  }

  /** 補助エージェントへの委譲で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void nativeToolReservationsConsumeTheSameRoleLimitAcrossGroups() throws Exception {
    when(client.authority()).thenReturn("local-native-transport");
    Files.writeString(
        root.resolve("review.yaml"),
        Files.readString(root.resolve("review.yaml"))
            .replace("tools: []", "tools: [readMultiFile]")
            .replace("maxSteps: 8", "maxSteps: 2"));
    var nativeRegistry =
        new SubAgentRegistry(
            root,
            new SubAgentDefinitionLoader(
                new SubAgentToolPolicy(Set.of("readMultiFile")), model -> true));
    assertThat(nativeRegistry.reload()).isEmpty();
    var nativeBudget = new java.util.concurrent.atomic.AtomicReference<LlmCallReservation>();
    doAnswer(
            call -> {
              nativeBudget.set(call.getArgument(0));
              return null;
            })
        .when(client)
        .nativeBudget(any(), anyInt());
    doAnswer(
            call -> {
              ((Runnable) call.getArgument(8)).run();
              String task = call.getArgument(3);
              tasks.add(task);
              assertThat(nativeBudget.get().tryReserve()).isTrue();
              return response(task);
            })
        .when(client)
        .infer(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            any(),
            anyInt(),
            anyInt(),
            any(),
            any());
    var backend =
        new ChatGptSubAgentBackend(
            client,
            new PlanProperties(true, root, Set.of("design-review")),
            nativeRegistry,
            guard,
            new CommandCancellationService(),
            new AgentEventFactory(clock),
            events::add,
            clock,
            runs,
            "codex-app-server");
    var request =
        new ChatGptSubAgentBackend.Request(
            "design-review",
            "codex-app-server",
            "model",
            "default",
            "Review",
            files(4, "x".repeat(70000)),
            "{\"decision\":\"accept\"}");
    var receipt = backend.run(request, owner, false, budget);
    assertThat(tasks).hasSize(1);
    assertThat(receipt.result().output()).contains("GROUP_CALL_BUDGET_EXCEEDED");
    verify(budget, times(2)).tryReserve();
  }

  /** 補助エージェントへの委譲の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void cancellationBeforeTheNextSendKeepsCompletedGroupAndSendsNothingFurther() throws Exception {
    var cancellation = new CommandCancellationService();
    var checks = new AtomicInteger();
    doAnswer(
            call -> {
              if (checks.incrementAndGet() == 2) cancellation.cancel();
              return null;
            })
        .when(guard)
        .checkAuthorizedPlanPolicy(anyString(), anyString(), eq(owner));
    doAnswer(
            call -> {
              if (call.<java.util.function.BooleanSupplier>getArgument(7).getAsBoolean())
                throw ChatGptException.of(ChatGptException.Code.CANCELLED);
              ((Runnable) call.getArgument(8)).run();
              String task = call.getArgument(3);
              tasks.add(task);
              return response(task);
            })
        .when(client)
        .infer(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            any(),
            anyInt(),
            anyInt(),
            any(),
            any());
    var backend =
        new ChatGptSubAgentBackend(
            client,
            new PlanProperties(true, root, Set.of("design-review")),
            registry,
            guard,
            cancellation,
            new AgentEventFactory(clock),
            events::add,
            clock,
            runs);
    var receipt = backend.run(request(files(4, "x".repeat(70000))), owner, false, budget);
    assertThat(tasks).hasSize(1);
    assertThat(receipt.state()).isEqualTo(ChatGptSubAgentBackend.State.STOP_CONFIRMED);
    assertThat(receipt.groups().getFirst().state())
        .isEqualTo(ChatGptSubAgentBackend.State.COMPLETED);
    assertThat(receipt.groups().getLast().error()).isEqualTo(ChatGptException.Code.CANCELLED);
  }

  /** 残量期限を利用することを確認する。 */
  @Test
  void subsequentGroupsUseTheRemainingDeadlineRatherThanANewTimeout() throws Exception {
    var durations = new ArrayList<Duration>();
    doAnswer(
            call -> {
              ((Runnable) call.getArgument(8)).run();
              durations.add(call.getArgument(4));
              String task = call.<List<PlanClient.Input>>getArgument(3).getFirst().content();
              tasks.add(task);
              if (durations.size() == 1) Thread.sleep(25);
              return response(task);
            })
        .when(client)
        .inferHistory(
            anyString(),
            anyString(),
            anyString(),
            anyList(),
            any(),
            anyInt(),
            anyInt(),
            any(),
            any());
    var backend = backend(guard);
    backend.configureExecution(
        new PlanExecutionProperties(true, 2, 0, 1, 262144, 1048576, Duration.ofSeconds(2), false));
    var receipt = backend.run(request(files(4, "x".repeat(70000))), owner, false, budget);
    assertThat(receipt.state()).isEqualTo(ChatGptSubAgentBackend.State.COMPLETED);
    assertThat(durations).hasSize(2);
    assertThat(durations.getLast()).isLessThan(durations.getFirst().minusMillis(10));
  }

  /** 補助エージェントへの委譲で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void aSingleEncodedOversizeOrUnreadableLaterSourceNeverStartsAPartialPlan() throws Exception {
    var names = files(2, "\"".repeat(100000));
    var failed = backend(guard).run(request(names), owner, false, budget);
    assertThat(failed.error()).isEqualTo(ChatGptException.Code.SEND_LIMIT);
    assertThat(failed.result().output()).contains("UNSPLITTABLE_INPUT");
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(client);
    clearInvocations(client);
    var missing =
        backend(guard).run(request(List.of(names.getFirst(), "missing.md")), owner, false, budget);
    assertThat(missing.error()).isEqualTo(ChatGptException.Code.SOURCE_UNAVAILABLE);
    verifyNoInteractions(client);
  }

  /** 補助エージェントへの委譲の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  @Tag("integration")
  void oneApprovalCoversTheWholePlanAndChangedLaterSourceNeedsNewApproval() throws Exception {
    var names = files(4, "x".repeat(70000));
    var source = new org.sqlite.SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + root.resolve("approvals.db"));
    var approvals = new ToolApprovalRepository(source, clock);
    var permission =
        new ToolPermissionGuard(
            new ToolPermissionPolicy(
                new ToolPermissionProperties(
                    true, Set.of(ActionCapability.READ), Set.of(), Map.of())),
            new AgentEventFactory(clock),
            events::add);
    permission.setApprovals(approvals);
    var backend = backend(permission);
    var pending = backend.run(request(names), owner, false, budget);
    assertThat(pending.error()).isEqualTo(ChatGptException.Code.CONSENT_REQUIRED);
    assertThat(tasks).isEmpty();
    var first = approvals.list("project").getFirst();
    approvals.decide("project", first.id(), true);
    Files.writeString(root.resolve(names.getLast()), "y".repeat(70000));
    var changed = backend.run(request(names), owner, false, budget);
    assertThat(changed.error()).isEqualTo(ChatGptException.Code.CONSENT_REQUIRED);
    assertThat(tasks).isEmpty();
    var current =
        approvals.list("project").stream()
            .filter(a -> a.status().equals("PENDING"))
            .findFirst()
            .orElseThrow();
    assertThat(current.id()).isNotEqualTo(first.id());
    approvals.decide("project", current.id(), true);
    var complete = backend.run(request(names), owner, false, budget);
    assertThat(complete.state()).isEqualTo(ChatGptSubAgentBackend.State.COMPLETED);
    assertThat(tasks).hasSize(2);
    assertThat(approvals.get("project", current.id()).status()).isEqualTo("CONSUMED");
    backend.run(request(names), owner, false, budget);
    assertThat(tasks).hasSize(2);
  }
}
