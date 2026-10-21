package dev.mikoto2000.rei.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.calls;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.core.policy.ActionCapability;
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
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;

/** タスク状態引数修復の結果と状態の契約を検証するテスト。 */
class TaskStateArgumentRepairTest {
  /** 検証に使う所有者。 */
  final AgentRunContext owner = new AgentRunContext("run", "session", Path.of("."), "project");

  /** 検証に使う承認一覧。 */
  final ToolApprovalRepository approvals = mock(ToolApprovalRepository.class);

  /** 検証に使う不正な。 */
  final String invalid = "{\"status\":\"IN_PROGRESS\",\"pending\":\"review,verify\"}";

  /** 検証に使う有効な。 */
  final String valid = "{\"status\":\"IN_PROGRESS\",\"pending\":[\"review\",\"verify\"]}";

  /** 検証に使う影響。 */
  final AtomicInteger effects = new AtomicInteger();

  /** 検証で使用する文脈を、指定された条件に合わせて用意する。 */
  RunExecutionContext context(OutputLimitRunBudget budget, boolean permissions) {
    var context =
        new RunExecutionContext(
            "run",
            budget,
            new ProgressEvaluator(Path.of("."), null),
            new AgentEventFactory(Clock.systemUTC()),
            event -> {});
    context.setRunContext(owner);
    if (permissions) {
      var guard =
          new ToolPermissionGuard(
              new ToolPermissionPolicy(
                  new ToolPermissionProperties(true, Set.of(), Set.of(), Map.of())),
              new AgentEventFactory(Clock.systemUTC()),
              event -> {});
      guard.setApprovals(approvals);
      context.setToolPermissionGuard(guard);
      when(approvals.request(eq("updateTaskState"), anyString(), eq(owner)))
          .thenReturn(
              new ToolApprovalRepository.Request(
                  "approval",
                  "project",
                  "session",
                  "run",
                  "updateTaskState",
                  "preview",
                  "PENDING",
                  Instant.now().plusSeconds(60)));
    }
    return context;
  }

  /** {@code callback} の条件を再現するための補助情報を返す。 */
  ToolCallback callback(String name, java.util.function.Function<String, String> effect) {
    return new ToolCallback() {
      /** 検証用に、会話の実行で参照するツール定義を返す。 */
      public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder().name(name).description(name).inputSchema("{}").build();
      }

      /** 検証用に、会話の実行の要求を実行し、呼び出し元へ結果を返す。 */
      public String call(String input) {
        return effect.apply(input);
      }
    };
  }

  /** 検証用に、指定された変更を、管理中の情報へ反映する。 */
  ToolCallback update() {
    return callback(
        "updateTaskState",
        input -> {
          effects.incrementAndGet();
          return "updated";
        });
  }

  /** 検証で使用するプロンプトを、指定された条件に合わせて用意する。 */
  Prompt prompt(RunExecutionContext context, ToolCallback... callbacks) {
    return new Prompt(
        List.of(new UserMessage("review")),
        ToolCallingChatOptions.builder()
            .toolCallbacks(List.of(callbacks))
            .toolContext(Map.of(RunExecutionContext.KEY, context))
            .build());
  }

  /** {@code toolCall} の条件を再現するための補助情報を返す。 */
  AssistantMessage.ToolCall toolCall(String id, String name, String input) {
    return new AssistantMessage.ToolCall(id, "function", name, input);
  }

  /** 検証用に、会話の実行の要求を実行し、呼び出し元へ結果を返す。 */
  ChatResponse call(String id, String input) {
    return new ChatResponse(
        List.of(
            new Generation(
                AssistantMessage.builder()
                    .toolCalls(List.of(toolCall(id, "updateTaskState", input)))
                    .build())));
  }

  /** 会話の実行の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void modelReceivesArrayGuidanceAndCorrectedCallRequiresNormalApproval() {
    var context = context(new OutputLimitRunBudget(2, 20), true);
    var modelCalls = new AtomicInteger();
    var model =
        new ChatModel() {
          /** 検証用に、会話の実行の要求を実行し、呼び出し元へ結果を返す。 */
          public ChatResponse call(Prompt prompt) {
            throw new AssertionError();
          }

          /** {@code stream} の条件を再現するための補助情報を返す。 */
          public Flux<ChatResponse> stream(Prompt prompt) {
            if (modelCalls.incrementAndGet() == 1)
              return Flux.just(TaskStateArgumentRepairTest.this.call("invalid", invalid));
            var feedback =
                prompt.getInstructions().stream()
                    .filter(ToolResponseMessage.class::isInstance)
                    .map(ToolResponseMessage.class::cast)
                    .flatMap(message -> message.getResponses().stream())
                    .findFirst()
                    .orElseThrow();
            assertTrue(feedback.responseData().contains("INVALID_TOOL_ARGUMENTS"));
            assertTrue(feedback.responseData().contains("pending: must be a JSON array"));
            assertEquals(0, effects.get());
            // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
            verifyNoInteractions(approvals);
            return Flux.just(TaskStateArgumentRepairTest.this.call("corrected", valid));
          }
        };
    assertThrows(
        ToolPermissionException.class,
        () -> new StagnationChatModel(model).call(prompt(context, update())));
    assertEquals(2, modelCalls.get());
    assertEquals(0, effects.get());
    verify(approvals).request("updateTaskState", valid, owner);
  }

  /** 会話の実行の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void validationAndDurableRepairLimitWorkWithoutPermissionGuard() {
    var budget = new OutputLimitRunBudget(2, 20, null, 0, 2);
    var context = context(budget, false);
    var loop = new ToolLoopSupport();
    for (int i = 0; i < 2; i++)
      loop.executeResumable(prompt(context, update()), call("invalid-" + i, invalid), context);
    var restored = new OutputLimitRunBudget(2, 20, null, 0, 2);
    restored.restore(budget.snapshot());
    var next = context(restored, false);
    var stopped =
        assertThrows(
            ExecutionStoppedException.class,
            () -> loop.executeResumable(prompt(next, update()), call("third", invalid), next));
    assertEquals(ExecutionStoppedException.Reason.TOOL_ARGUMENT_REPAIR_EXHAUSTED, stopped.reason());
    assertEquals(0, effects.get());
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(approvals);
  }

  /** 会話の実行で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void successfulSiblingIsPreservedAndNotRepeatedForARepairedCall() {
    var context = context(new OutputLimitRunBudget(2, 20), false);
    var siblings = new AtomicInteger();
    var sibling =
        callback(
            "readMultiFile",
            input -> {
              siblings.incrementAndGet();
              return "read";
            });
    var update = update();
    var calls =
        List.of(
            toolCall("read", "readMultiFile", "{}"), toolCall("bad", "updateTaskState", invalid));
    var response =
        new ChatResponse(
            List.of(new Generation(AssistantMessage.builder().toolCalls(calls).build())));
    var result =
        new ToolLoopSupport().executeResumable(prompt(context, sibling, update), response, context);
    assertEquals(1, siblings.get());
    assertEquals(0, effects.get());
    assertFalse(result.returnDirect());
    var feedback = (ToolResponseMessage) result.conversationHistory().getLast();
    assertEquals(2, feedback.getResponses().size());
    assertEquals("read", feedback.getResponses().getFirst().responseData());
    new ToolLoopSupport()
        .executeResumable(prompt(context, sibling, update), call("fixed", valid), context);
    assertEquals(1, siblings.get());
    assertEquals(1, effects.get());
  }

  /** 会話の実行で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void postExecutionArgumentExceptionIsNeverAutomaticallyRepaired() {
    var context = context(new OutputLimitRunBudget(2, 20), false);
    var failure = new IllegalArgumentException("execution failed after effect");
    var update =
        callback(
            "updateTaskState",
            input -> {
              effects.incrementAndGet();
              throw failure;
            });
    assertSame(
        failure,
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new ToolLoopSupport()
                    .executeResumable(prompt(context, update), call("valid", valid), context)));
    assertEquals(1, effects.get());
    assertEquals(0, context.budgetSnapshot().argumentCorrections());
  }

  /** 会話の実行で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void validCallWithPolicyDenialIsNotAnArgumentRepair() {
    var context = context(new OutputLimitRunBudget(2, 20), false);
    context.setToolPermissionGuard(
        new ToolPermissionGuard(
            new ToolPermissionPolicy(
                new ToolPermissionProperties(
                    true, Set.of(), Set.of(ActionCapability.LOCAL_WRITE), Map.of())),
            new AgentEventFactory(Clock.systemUTC()),
            event -> {}));
    var modelCalls = new AtomicInteger();
    var model =
        new ChatModel() {
          /** 検証用に、会話の実行の要求を実行し、呼び出し元へ結果を返す。 */
          public ChatResponse call(Prompt prompt) {
            throw new AssertionError();
          }

          /** {@code stream} の条件を再現するための補助情報を返す。 */
          public Flux<ChatResponse> stream(Prompt prompt) {
            modelCalls.incrementAndGet();
            return Flux.just(TaskStateArgumentRepairTest.this.call("denied", valid));
          }
        };
    assertThrows(
        ToolPermissionException.class,
        () -> new StagnationChatModel(model).call(prompt(context, update())));
    assertEquals(1, modelCalls.get());
    assertEquals(0, effects.get());
    assertEquals(0, context.budgetSnapshot().argumentCorrections());
  }
}
