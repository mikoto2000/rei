package dev.mikoto2000.rei.core.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.calls;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.chatgpt.ChatGptException;
import dev.mikoto2000.rei.chatgpt.PlanDelegationProperties;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.stagnation.ProgressEvaluator;
import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;
import dev.mikoto2000.rei.core.stagnation.StagnationChatModel;
import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventPublisher;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.event.ToolFailedPayload;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import dev.mikoto2000.rei.subagent.DelegationToolPermissionResolver;
import dev.mikoto2000.rei.subagent.SubAgentDefinition;
import dev.mikoto2000.rei.subagent.SubAgentRegistry;
import dev.mikoto2000.rei.subagent.SubAgentToolPolicy;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import reactor.core.publisher.Flux;

/** 委譲Preflight失敗情報の結果と状態の契約を検証するテスト。 */
class DelegationPreflightFailureTest {
  /** 判断基準失敗情報を伝えることを確認する。 */
  @Test
  void argumentRepairFollowedByForbiddenAccountReportsPolicyFailureWithoutAnotherRepair() {
    var owner = new AgentRunContext("run", "session", Path.of("."), "project");
    var approvals = mock(ToolApprovalRepository.class);
    var automatic = new PlanDelegationProperties(true, Set.of("default"), Set.of("allowed-model"));
    var registry = mock(SubAgentRegistry.class);
    when(registry.findById("reviewer"))
        .thenReturn(
            Optional.of(
                new SubAgentDefinition(
                    "reviewer",
                    "reviewer",
                    "review",
                    "review",
                    List.of(),
                    "local-model",
                    4,
                    Duration.ofSeconds(30),
                    Path.of("reviewer.yaml"))));
    var beans = new StaticListableBeanFactory();
    beans.addBean("registry", registry);
    beans.addBean("tools", new SubAgentToolPolicy(Set.of()));
    var resolver =
        new DelegationToolPermissionResolver(
            beans.getBeanProvider(SubAgentRegistry.class),
            beans.getBeanProvider(SubAgentToolPolicy.class));
    resolver.setAutomatic(automatic);
    var policy =
        new ToolPermissionPolicy(new ToolPermissionProperties(true, Set.of(), Set.of(), Map.of()));
    policy.setDelegationResolver(resolver);
    var recorded = new CopyOnWriteArrayList<AgentEvent>();
    var refusalBoundaries = new AtomicInteger();
    var publisher =
        new AgentEventPublisher() {
          /** 検証用に、状態や結果を購読先へ通知する。 */
          public void publish(AgentEvent event) {
            recorded.add(event);
          }

          /** 検証用に、実行状況を利用先で把握できるよう、境界条件を通知する。 */
          public void publishBoundary(AgentEvent event) {
            if (event.payload() instanceof ToolFailedPayload failed
                && "chatgpt_permission_denied".equals(failed.error().code()))
              refusalBoundaries.incrementAndGet();
            publish(event);
          }
        };
    var factory = new AgentEventFactory(Clock.systemUTC());
    var guard = new ToolPermissionGuard(policy, factory, publisher);
    guard.setApprovals(approvals);
    var budget = new OutputLimitRunBudget(2, 20);
    var context =
        new RunExecutionContext(
            "run", budget, new ProgressEvaluator(Path.of("."), null), factory, publisher);
    context.setRunContext(owner);
    context.setToolPermissionGuard(guard);
    var effects = new AtomicInteger();
    var tool =
        new ToolCallback() {
          /** 検証用に、ツール利用の権限制御で参照するツール定義を返す。 */
          public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                .name("delegateTask")
                .description("delegate")
                .inputSchema("{}")
                .build();
          }

          /** 検証用に、ツール利用の権限制御の要求を実行し、呼び出し元へ結果を返す。 */
          public String call(String input) {
            effects.incrementAndGet();
            throw new AssertionError("A forbidden account must never be executed or substituted");
          }
        };
    String corrected =
        "{\"agent\":\"reviewer\",\"task\":\"review\",\"backendId\":\"chatgpt-plan\",\"accountRef\":\"private-forbidden-account\",\"modelId\":\"allowed-model\",\"sourceRefs\":[\"docs/a.md\"],\"expectedOutput\":\"{\\\"unverified\\\":[]}\"}";
    String malformed =
        corrected.substring(0, corrected.length() - 1) + ",\"context\":\"private-context\"}";
    var calls = new AtomicInteger();
    var model =
        new ChatModel() {
          /** 検証用に、ツール利用の権限制御の要求を実行し、呼び出し元へ結果を返す。 */
          public ChatResponse call(Prompt prompt) {
            throw new AssertionError();
          }

          /** {@code stream} の条件を再現するための補助情報を返す。 */
          public Flux<ChatResponse> stream(Prompt prompt) {
            int number = calls.incrementAndGet();
            if (number == 2) {
              var feedback =
                  prompt.getInstructions().stream()
                      .filter(ToolResponseMessage.class::isInstance)
                      .map(ToolResponseMessage.class::cast)
                      .flatMap(message -> message.getResponses().stream())
                      .findFirst()
                      .orElseThrow()
                      .responseData();
              assertTrue(feedback.contains("INVALID_TOOL_ARGUMENTS"));
              assertTrue(feedback.contains("request.context"));
              assertFalse(feedback.contains("private-context"));
            }
            assertTrue(number <= 2, "A policy refusal must not trigger a third model request");
            return Flux.just(
                new ChatResponse(
                    List.of(
                        new Generation(
                            AssistantMessage.builder()
                                .toolCalls(
                                    List.of(
                                        new AssistantMessage.ToolCall(
                                            number == 1 ? "malformed" : "corrected",
                                            "function",
                                            "delegateTask",
                                            number == 1 ? malformed : corrected)))
                                .build()))));
          }
        };
    var prompt =
        new Prompt(
            List.of(new UserMessage("ChatGPT にレビューして")),
            ToolCallingChatOptions.builder()
                .toolCallbacks(List.of(tool))
                .toolContext(Map.of(RunExecutionContext.KEY, context))
                .build());
    var error =
        assertThrows(ChatGptException.class, () -> new StagnationChatModel(model).call(prompt));
    assertEquals(ChatGptException.PermissionReason.ACCOUNT_NOT_ALLOWED, error.permissionReason());
    assertEquals(2, calls.get());
    assertEquals(1, budget.snapshot().argumentCorrections());
    assertEquals(0, effects.get());
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(approvals);
    assertEquals(Set.of("allowed-model"), automatic.allowedModels());
    var failures =
        recorded.stream().filter(event -> event.payload() instanceof ToolFailedPayload).toList();
    assertEquals(2, failures.size());
    assertEquals(1, refusalBoundaries.get());
    var refused =
        failures.stream()
            .filter(
                event ->
                    ((ToolFailedPayload) event.payload())
                        .error()
                        .code()
                        .equals("chatgpt_permission_denied"))
            .findFirst()
            .orElseThrow();
    var payload = (ToolFailedPayload) refused.payload();
    assertEquals("corrected", payload.toolCallId());
    assertEquals("delegateTask", payload.toolName());
    assertEquals("run", refused.runId());
    assertEquals("project", refused.projectId());
    assertEquals("session", refused.sessionId());
    assertTrue(payload.error().message().contains("ACCOUNT_NOT_ALLOWED"));
    assertTrue(payload.error().message().contains("allowed-accounts"));
    assertFalse(payload.error().message().contains("private-forbidden-account"));
    assertFalse(payload.error().message().contains("private-context"));
    assertTrue(recorded.stream().noneMatch(event -> event.type() == AgentEventType.TOOL_STARTED));
  }
}
