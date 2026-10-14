package dev.mikoto2000.rei.core.chat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import dev.mikoto2000.rei.core.policy.*;
import dev.mikoto2000.rei.core.stagnation.*;
import dev.mikoto2000.rei.event.*;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;

class ShellFileReadArgumentRepairTest {
  final AgentRunContext owner = new AgentRunContext("run", "session", Path.of("."), "project");
  final ToolApprovalRepository approvals = mock(ToolApprovalRepository.class);
  final AtomicInteger shellExecutions = new AtomicInteger(), reads = new AtomicInteger();
  final String input = "{\"request\":{\"command\":\"git status; powershell -Command \\\"Get-Content -Raw docs/a.md\\\"\"}}";

  RunExecutionContext context(boolean guarded) {
    var context = new RunExecutionContext("run", new OutputLimitRunBudget(2, 20),
        new ProgressEvaluator(Path.of("."), null), new AgentEventFactory(Clock.systemUTC()), event -> {});
    context.setRunContext(owner);
    if (guarded) {
      var guard = new ToolPermissionGuard(new ToolPermissionPolicy(new ToolPermissionProperties(true, Set.of(), Set.of(), Map.of())),
          new AgentEventFactory(Clock.systemUTC()), event -> {});
      guard.setApprovals(approvals); context.setToolPermissionGuard(guard);
    }
    return context;
  }
  ToolCallback callback(String name, AtomicInteger effects) { return new ToolCallback() {
    public ToolDefinition getToolDefinition() { return ToolDefinition.builder().name(name).description(name).inputSchema("{}").build(); }
    public String call(String input) { effects.incrementAndGet(); return "ok"; }
  }; }
  Prompt prompt(RunExecutionContext context) {
    return new Prompt(List.of(new UserMessage("Read docs/a.md")), ToolCallingChatOptions.builder()
        .toolCallbacks(List.of(callback("runCommand", shellExecutions), callback("readMultiFile", reads)))
        .toolContext(Map.of(RunExecutionContext.KEY, context)).build());
  }
  AssistantMessage.ToolCall call(String id, String name, String args) { return new AssistantMessage.ToolCall(id, "function", name, args); }

  @Test void blocksBeforeApprovalAndReturnsRepairWithoutRepeatingSuccessfulSibling() {
    for (boolean guarded : List.of(false, true)) {
      var context = context(guarded); var prompt = prompt(context); var loop = new ToolLoopSupport();
      int before = reads.get();
      var calls = List.of(call("read", "readMultiFile", "{}"), call("bad", "runCommand", input));
      var response = new ChatResponse(List.of(new Generation(AssistantMessage.builder().toolCalls(calls).build())));
      var result = loop.executeResumable(prompt, response, context);
      var feedback = (ToolResponseMessage) result.conversationHistory().getLast();
      assertEquals(2, feedback.getResponses().size());
      assertEquals("ok", feedback.getResponses().getFirst().responseData());
      String correction = feedback.getResponses().getLast().responseData();
      assertTrue(correction.contains("INVALID_TOOL_ARGUMENTS"));
      assertTrue(correction.contains("\"executed\":false"));
      assertTrue(correction.contains("readMultiFile")); assertTrue(correction.contains("nextRead"));
      assertEquals(before + 1, reads.get()); assertEquals(0, shellExecutions.get());
      assertEquals(1, context.budgetSnapshot().argumentCorrections()); verifyNoInteractions(approvals);
      var fixed = new ChatResponse(List.of(new Generation(AssistantMessage.builder()
          .toolCalls(List.of(call("fixed", "readMultiFile", "{\"files\":[{\"path\":\"docs/a.md\"}]}"))).build())));
      loop.executeResumable(prompt, fixed, context);
      assertEquals(before + 2, reads.get()); assertEquals(0, shellExecutions.get());
    }
  }

  @Test void savedApprovedCommandIsRevalidatedBeforeReplay() {
    var context = context(true);
    var pending = new RunContinuation.PendingTools("", List.of(call("saved", "runCommand", input)), List.of());
    var result = new ToolLoopSupport().executePending(prompt(context), pending, context);
    var feedback = (ToolResponseMessage) result.conversationHistory().getLast();
    assertTrue(feedback.getResponses().getFirst().responseData().contains("INVALID_TOOL_ARGUMENTS"));
    assertEquals(0, shellExecutions.get()); verifyNoInteractions(approvals);
  }

  @Test void guardRejectsBeforeConsumingOrRequestingApproval() {
    var guard = new ToolPermissionGuard(new ToolPermissionPolicy(new ToolPermissionProperties(true, Set.of(), Set.of(), Map.of())),
        new AgentEventFactory(Clock.systemUTC()), event -> {});
    guard.setApprovals(approvals);
    assertThrows(InvalidToolArgumentsException.class, () -> guard.check("runCommand", input, owner));
    verifyNoInteractions(approvals);
  }
}
