package dev.mikoto2000.rei.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.calls;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.core.policy.ToolApprovalRepository;
import dev.mikoto2000.rei.core.policy.ToolPermissionException;
import dev.mikoto2000.rei.core.policy.ToolPermissionGuard;
import dev.mikoto2000.rei.core.policy.ToolPermissionPolicy;
import dev.mikoto2000.rei.core.policy.ToolPermissionProperties;
import dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException;
import dev.mikoto2000.rei.core.stagnation.ProgressEvaluator;
import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;
import dev.mikoto2000.rei.core.stagnation.StagnationChatModel;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;

class DelegationArgumentRepairTest {
  final AgentRunContext owner = new AgentRunContext("run", "session", Path.of("."), "project");
  final ToolApprovalRepository approvals = mock(ToolApprovalRepository.class);
  final ToolPermissionGuard guard =
      new ToolPermissionGuard(
          new ToolPermissionPolicy(
              new ToolPermissionProperties(true, Set.of(), Set.of(), Map.of())),
          new AgentEventFactory(Clock.systemUTC()),
          e -> {});
  final AtomicInteger invoked = new AtomicInteger(), effects = new AtomicInteger();
  final String valid =
      "{\"agent\":\"reviewer\",\"task\":\"review\",\"backendId\":\"chatgpt-plan\",\"accountRef\":\"default\",\"modelId\":\"model\",\"sourceRefs\":[\"docs/a.md\"],\"expectedOutput\":\"{\\\"unverified\\\":[]}\"}";

  RunExecutionContext context(OutputLimitRunBudget budget) {
    guard.setApprovals(approvals);
    when(approvals.request(eq("delegateTask"), anyString(), eq(owner)))
        .thenReturn(
            new ToolApprovalRepository.Request(
                "approval",
                "project",
                "session",
                "run",
                "delegateTask",
                "preview",
                "PENDING",
                Instant.now().plusSeconds(60)));
    var ctx =
        new RunExecutionContext(
            "run",
            budget,
            new ProgressEvaluator(Path.of("."), null),
            new AgentEventFactory(Clock.systemUTC()),
            e -> {});
    ctx.setRunContext(owner);
    ctx.setToolPermissionGuard(guard);
    return ctx;
  }

  ToolCallback callback() {
    return new ToolCallback() {
      public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
            .name("delegateTask")
            .description("delegate")
            .inputSchema("{}")
            .build();
      }

      public String call(String input) {
        invoked.incrementAndGet();
        guard.check("delegateTask", input, owner);
        effects.incrementAndGet();
        return "done";
      }
    };
  }

  Prompt prompt(RunExecutionContext ctx) {
    return new Prompt(
        List.of(new UserMessage("ChatGPT にレビューして")),
        OpenAiChatOptions.builder()
            .toolCallbacks(callback())
            .toolContext(Map.of(RunExecutionContext.KEY, ctx))
            .build());
  }

  ChatResponse call(String id, String input) {
    return new ChatResponse(
        List.of(
            new Generation(
                AssistantMessage.builder()
                    .toolCalls(
                        List.of(
                            new AssistantMessage.ToolCall(id, "function", "delegateTask", input)))
                    .build())));
  }

  @Test
  void modelReceivesValidationResultAndCorrectedCallStillRequiresApproval() {
    var ctx = context(new OutputLimitRunBudget(2, 20));
    var calls = new AtomicInteger();
    var model =
        new ChatModel() {
          public ChatResponse call(Prompt p) {
            throw new AssertionError();
          }

          public Flux<ChatResponse> stream(Prompt p) {
            if (calls.incrementAndGet() == 1)
              return Flux.just(
                  DelegationArgumentRepairTest.this.call(
                      "first", valid.replace("\"accountRef\":\"default\",", "")));
            assertTrue(
                p.getInstructions().stream()
                        .filter(ToolResponseMessage.class::isInstance)
                        .map(Message::getText)
                        .anyMatch(t -> t != null && t.contains("INVALID_TOOL_ARGUMENTS"))
                    || p.getInstructions().stream()
                        .filter(ToolResponseMessage.class::isInstance)
                        .map(ToolResponseMessage.class::cast)
                        .flatMap(t -> t.getResponses().stream())
                        .anyMatch(t -> t.responseData().contains("ACCOUNT_REQUIRED")));
            return Flux.just(DelegationArgumentRepairTest.this.call("corrected", valid));
          }
        };
    assertThrows(
        ToolPermissionException.class, () -> new StagnationChatModel(model).call(prompt(ctx)));
    assertEquals(2, calls.get());
    assertEquals(0, invoked.get());
    assertEquals(0, effects.get());
    verify(approvals).request("delegateTask", valid, owner);
  }

  @Test
  void correctionLimitSurvivesSnapshotRestoreAndNeverExecutesInvalidCalls() {
    var budget = new OutputLimitRunBudget(2, 20, null, 0, 2);
    var ctx = context(budget);
    var loop = new ToolLoopSupport();
    for (int i = 0; i < 2; i++) loop.executeResumable(prompt(ctx), call("bad" + i, "{}"), ctx);
    assertEquals(0, invoked.get());
    verifyNoInteractions(approvals);
    var restored = new OutputLimitRunBudget(2, 20, null, 0, 2);
    restored.restore(budget.snapshot());
    var next = context(restored);
    var stopped =
        assertThrows(
            ExecutionStoppedException.class,
            () -> loop.executeResumable(prompt(next), call("third", "{}"), next));
    assertEquals(ExecutionStoppedException.Reason.TOOL_ARGUMENT_REPAIR_EXHAUSTED, stopped.reason());
  }

  @Test
  void policyDenialIsNotConvertedIntoAnArgumentRepair() {
    var budget = new OutputLimitRunBudget(2, 20);
    var ctx = context(budget);
    ctx.setToolPermissionGuard(
        new ToolPermissionGuard(
            new ToolPermissionPolicy(new ToolPermissionProperties(true, null, null, null)),
            new AgentEventFactory(Clock.systemUTC()),
            e -> {}));
    var calls = new AtomicInteger();
    var model =
        new ChatModel() {
          public ChatResponse call(Prompt p) {
            throw new AssertionError();
          }

          public Flux<ChatResponse> stream(Prompt p) {
            calls.incrementAndGet();
            return Flux.just(DelegationArgumentRepairTest.this.call("denied", valid));
          }
        };
    assertThrows(
        ToolPermissionException.class, () -> new StagnationChatModel(model).call(prompt(ctx)));
    assertEquals(1, calls.get());
    assertEquals(0, budget.snapshot().argumentCorrections());
    assertEquals(0, effects.get());
  }

  @Test
  void toolDefinitionsExplainConditionalRequiredFields() {
    var registry = mock(dev.mikoto2000.rei.subagent.SubAgentRegistry.class);
    when(registry.list()).thenReturn(List.of());
    var tools =
        new dev.mikoto2000.rei.subagent.SubAgentTools(
            mock(dev.mikoto2000.rei.subagent.SubAgentRunner.class), registry);
    var single = tools.callback("delegateTask").getToolDefinition();
    assertTrue(single.description().contains("REQUIRED accountRef, modelId"));
    assertTrue(single.inputSchema().contains("Never omit or send blank for ChatGPT"));
    assertTrue(single.inputSchema().contains("preserving case and punctuation"));
    assertTrue(
        tools
            .callback("delegateTasks")
            .getToolDefinition()
            .description()
            .contains("Every ChatGPT request MUST"));
  }
}
