package dev.mikoto2000.rei.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.calls;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import dev.mikoto2000.rei.core.policy.InvalidToolArgumentsException;
import dev.mikoto2000.rei.core.policy.ToolApprovalRepository;
import dev.mikoto2000.rei.core.policy.ToolPermissionGuard;
import dev.mikoto2000.rei.core.policy.ToolPermissionPolicy;
import dev.mikoto2000.rei.core.policy.ToolPermissionProperties;
import dev.mikoto2000.rei.core.stagnation.ProgressEvaluator;
import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/** シェルファイル読み取り引数修復の結果と状態の契約を検証するテスト。 */
class ShellFileReadArgumentRepairTest {
  /** 検証に使う所有者。 */
  final AgentRunContext owner = new AgentRunContext("run", "session", Path.of("."), "project");

  /** 検証に使う承認一覧。 */
  final ToolApprovalRepository approvals = mock(ToolApprovalRepository.class);

  /** 検証に使うシェル実行一覧、読み取る。 */
  final AtomicInteger shellExecutions = new AtomicInteger(), reads = new AtomicInteger();

  /** 検証に使う入力。 */
  final String input =
      "{\"request\":{\"command\":\"git status; powershell -Command \\\"Get-Content -Raw docs/a.md\\\"\"}}";

  /** 検証で使用する文脈を、指定された条件に合わせて用意する。 */
  RunExecutionContext context(boolean guarded) {
    var context =
        new RunExecutionContext(
            "run",
            new OutputLimitRunBudget(2, 20),
            new ProgressEvaluator(Path.of("."), null),
            new AgentEventFactory(Clock.systemUTC()),
            event -> {});
    context.setRunContext(owner);
    if (guarded) {
      var guard =
          new ToolPermissionGuard(
              new ToolPermissionPolicy(
                  new ToolPermissionProperties(true, Set.of(), Set.of(), Map.of())),
              new AgentEventFactory(Clock.systemUTC()),
              event -> {});
      guard.setApprovals(approvals);
      context.setToolPermissionGuard(guard);
    }
    return context;
  }

  /** {@code callback} の条件を再現するための補助情報を返す。 */
  ToolCallback callback(String name, AtomicInteger effects) {
    return new ToolCallback() {
      /** 検証用に、会話の実行で参照するツール定義を返す。 */
      public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder().name(name).description(name).inputSchema("{}").build();
      }

      /** 検証用に、会話の実行の要求を実行し、呼び出し元へ結果を返す。 */
      public String call(String input) {
        effects.incrementAndGet();
        return "ok";
      }
    };
  }

  /** 検証で使用するプロンプトを、指定された条件に合わせて用意する。 */
  Prompt prompt(RunExecutionContext context) {
    return new Prompt(
        List.of(new UserMessage("Read docs/a.md")),
        ToolCallingChatOptions.builder()
            .toolCallbacks(
                List.of(callback("runCommand", shellExecutions), callback("readMultiFile", reads)))
            .toolContext(Map.of(RunExecutionContext.KEY, context))
            .build());
  }

  /** 検証用に、会話の実行の要求を実行し、呼び出し元へ結果を返す。 */
  AssistantMessage.ToolCall call(String id, String name, String args) {
    return new AssistantMessage.ToolCall(id, "function", name, args);
  }

  /** 会話の実行の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void blocksBeforeApprovalAndReturnsRepairWithoutRepeatingSuccessfulSibling() {
    for (boolean guarded : List.of(false, true)) {
      var context = context(guarded);
      var prompt = prompt(context);
      var loop = new ToolLoopSupport();
      int before = reads.get();
      var calls = List.of(call("read", "readMultiFile", "{}"), call("bad", "runCommand", input));
      var response =
          new ChatResponse(
              List.of(new Generation(AssistantMessage.builder().toolCalls(calls).build())));
      var result = loop.executeResumable(prompt, response, context);
      var feedback = (ToolResponseMessage) result.conversationHistory().getLast();
      assertEquals(2, feedback.getResponses().size());
      assertEquals("ok", feedback.getResponses().getFirst().responseData());
      String correction = feedback.getResponses().getLast().responseData();
      assertTrue(correction.contains("INVALID_TOOL_ARGUMENTS"));
      assertTrue(correction.contains("\"executed\":false"));
      assertTrue(correction.contains("readMultiFile"));
      assertTrue(correction.contains("nextRead"));
      assertEquals(before + 1, reads.get());
      assertEquals(0, shellExecutions.get());
      assertEquals(1, context.budgetSnapshot().argumentCorrections());
      // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
      verifyNoInteractions(approvals);
      var fixed =
          new ChatResponse(
              List.of(
                  new Generation(
                      AssistantMessage.builder()
                          .toolCalls(
                              List.of(
                                  call(
                                      "fixed",
                                      "readMultiFile",
                                      "{\"files\":[{\"path\":\"docs/a.md\"}]}")))
                          .build())));
      loop.executeResumable(prompt, fixed, context);
      assertEquals(before + 2, reads.get());
      assertEquals(0, shellExecutions.get());
    }
  }

  /** 会話の実行で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void savedApprovedCommandIsRevalidatedBeforeReplay() {
    var context = context(true);
    var pending =
        new RunContinuation.PendingTools(
            "", List.of(call("saved", "runCommand", input)), List.of());
    var result = new ToolLoopSupport().executePending(prompt(context), pending, context);
    var feedback = (ToolResponseMessage) result.conversationHistory().getLast();
    assertTrue(
        feedback.getResponses().getFirst().responseData().contains("INVALID_TOOL_ARGUMENTS"));
    assertEquals(0, shellExecutions.get());
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(approvals);
  }

  /** 会話の実行の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void guardRejectsBeforeConsumingOrRequestingApproval() {
    var guard =
        new ToolPermissionGuard(
            new ToolPermissionPolicy(
                new ToolPermissionProperties(true, Set.of(), Set.of(), Map.of())),
            new AgentEventFactory(Clock.systemUTC()),
            event -> {});
    guard.setApprovals(approvals);
    assertThrows(
        InvalidToolArgumentsException.class, () -> guard.check("runCommand", input, owner));
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(approvals);
  }
}
