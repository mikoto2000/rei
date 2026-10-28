package dev.mikoto2000.rei.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.core.FindFileRequest;
import dev.mikoto2000.rei.core.policy.ToolPermissionGuard;
import dev.mikoto2000.rei.core.policy.ToolPermissionPolicy;
import dev.mikoto2000.rei.core.policy.ToolPermissionProperties;
import dev.mikoto2000.rei.core.stagnation.ProgressEvaluator;
import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;
import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.event.ToolEventCallbackProvider;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

/** findFile の不正な引数が実行失敗にならず、同じ実行で補正できることを確認する。 */
class FindFileArgumentRepairTest {
  /** 実際の要求型への変換を行い、検索の代わりに実行回数を記録する。 */
  public static class FileSearch {
    /** 実行回数。 */
    int calls;

    /** 検索件数を返すテスト用ツール。 */
    @Tool(name = "findFile", description = "Find files")
    public int findFile(FindFileRequest request) {
      calls++;
      return request.maxResults() == null ? 50 : request.maxResults();
    }
  }

  /** 上限超過を実行前に検出し、既存の補正予算を使って再呼び出しできる。 */
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void returnsConstraintAndExecutesCorrectedCallInSameRun(boolean guarded) {
    var events = new ArrayList<AgentEvent>();
    var factory = new AgentEventFactory(Clock.systemUTC());
    var context =
        new RunExecutionContext(
            "run",
            new dev.mikoto2000.rei.llm.OutputLimitRunBudget(2, 20),
            new ProgressEvaluator(Path.of("."), null),
            factory,
            events::add);
    if (guarded)
      context.setToolPermissionGuard(
          new ToolPermissionGuard(
              new ToolPermissionPolicy(
                  new ToolPermissionProperties(false, Set.of(), Set.of(), Map.of())),
              factory,
              events::add));
    var search = new FileSearch();
    var provider = MethodToolCallbackProvider.builder().toolObjects(search).build();
    var prompt =
        new Prompt(
            "Find Markdown files",
            ToolCallingChatOptions.builder()
                .toolCallbacks(
                    new ToolEventCallbackProvider(provider, factory, events::add)
                        .getToolCallbacks())
                .toolContext(Map.of(RunExecutionContext.KEY, context))
                .build());
    var loop = new ToolLoopSupport();
    var result = loop.executeResumable(prompt, response("bad", 501), context);
    String error =
        ((ToolResponseMessage) result.conversationHistory().getLast())
            .getResponses()
            .getFirst()
            .responseData();
    assertTrue(error.contains("INVALID_TOOL_ARGUMENTS"));
    assertTrue(error.contains("between 1 and 500"));
    assertTrue(error.contains("\"executed\":false"));
    assertFalse(result.returnDirect());
    assertEquals(0, search.calls);
    assertEquals(1, context.budgetSnapshot().argumentCorrections());
    assertEquals(
        List.of(AgentEventType.TOOL_FAILED), events.stream().map(AgentEvent::type).toList());
    assertEquals(
        "invalid_tool_arguments",
        ((dev.mikoto2000.rei.event.ToolFailedPayload) events.getFirst().payload()).error().code());

    var fixed =
        loop.executeResumable(
            new Prompt(result.conversationHistory(), prompt.getOptions()),
            response("fixed", 500),
            context);
    assertEquals(
        "500",
        ((ToolResponseMessage) fixed.conversationHistory().getLast())
            .getResponses()
            .getFirst()
            .responseData());
    assertEquals(1, search.calls);
    assertEquals(1, context.budgetSnapshot().argumentCorrections());
    assertEquals(
        List.of(
            AgentEventType.TOOL_FAILED, AgentEventType.TOOL_STARTED, AgentEventType.TOOL_COMPLETED),
        events.stream().map(AgentEvent::type).toList());
  }

  /** LLM による findFile の呼び出しを構築する。 */
  private ChatResponse response(String id, int limit) {
    return new ChatResponse(
        List.of(
            new Generation(
                AssistantMessage.builder()
                    .toolCalls(
                        List.of(
                            new AssistantMessage.ToolCall(
                                id,
                                "function",
                                "findFile",
                                "{\"request\":{\"keywords\":[\".md\"],\"maxResults\":"
                                    + limit
                                    + "}}")))
                    .build())));
  }
}
