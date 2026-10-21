package dev.mikoto2000.rei.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.core.policy.ToolPermissionGuard;
import dev.mikoto2000.rei.core.policy.ToolPermissionPolicy;
import dev.mikoto2000.rei.core.policy.ToolPermissionProperties;
import dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException;
import dev.mikoto2000.rei.core.stagnation.ProgressEvaluator;
import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;
import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.event.ToolEventCallbackProvider;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import dev.mikoto2000.rei.urlfetch.UrlContentFetchService;
import dev.mikoto2000.rei.websearch.WebPageExtractor;
import dev.mikoto2000.rei.websearch.WebSearchAndReadService;
import dev.mikoto2000.rei.websearch.WebSearchProperties;
import dev.mikoto2000.rei.websearch.WebSearchService;
import dev.mikoto2000.rei.websearch.WebSearchTools;
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
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

/** Web検索引数修復の結果と状態の契約を検証するテスト。 */
class WebSearchArgumentRepairTest {
  /** 検証に使う検索。 */
  final WebSearchService search = mock(WebSearchService.class);

  /** 検証に使う取得。 */
  final UrlContentFetchService fetch = mock(UrlContentFetchService.class);

  /** 通知された出来事と順序を検証するための記録。 */
  final List<AgentEvent> events = new ArrayList<>();

  /** 検証に使う生成元。 */
  final AgentEventFactory factory = new AgentEventFactory(Clock.systemUTC());

  /** 検証に使う繰り返し。 */
  final ToolLoopSupport loop = new ToolLoopSupport();

  /** 検証で使用する文脈を、指定された条件に合わせて用意する。 */
  RunExecutionContext context(boolean guarded) {
    var context =
        new RunExecutionContext(
            "run",
            new OutputLimitRunBudget(2, 20),
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
    return context;
  }

  /** 検証で使用するプロンプトを、指定された条件に合わせて用意する。 */
  Prompt prompt(RunExecutionContext context) {
    var properties = new WebSearchProperties();
    var service = new WebSearchAndReadService(search, fetch, new WebPageExtractor(), properties);
    var provider =
        MethodToolCallbackProvider.builder()
            .toolObjects(new WebSearchTools(search, service))
            .build();
    return new Prompt(
        "Research Void",
        ToolCallingChatOptions.builder()
            .toolCallbacks(
                new ToolEventCallbackProvider(provider, factory, events::add).getToolCallbacks())
            .toolContext(Map.of(RunExecutionContext.KEY, context))
            .build());
  }

  /** 検証で使用する応答を、指定された条件に合わせて用意する。 */
  ChatResponse response(String id, String fields) {
    return new ChatResponse(
        List.of(
            new Generation(
                AssistantMessage.builder()
                    .toolCalls(
                        List.of(
                            new AssistantMessage.ToolCall(
                                id,
                                "function",
                                "webSearchAndRead",
                                "{\"request\":{\"query\":\"Void full-stack framework\","
                                    + fields
                                    + "}}")))
                    .build())));
  }

  /** 不正な引数の制約を返し、修正した要求を同じ実行内で受け付けられることを確認する。 */
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void returnsConstraintAndExecutesCorrectedCallInSameRun(boolean guarded) throws Exception {
    var context = context(guarded);
    var prompt = prompt(context);
    var bad = response("bad", "\"maxResults\":10,\"readTop\":0");
    var result = loop.executeResumable(prompt, bad, context);
    var feedback = (ToolResponseMessage) result.conversationHistory().getLast();
    String error = feedback.getResponses().getFirst().responseData();
    assertTrue(error.contains("INVALID_TOOL_ARGUMENTS"));
    assertTrue(error.contains("maxResults must be between 1 and 5"));
    assertTrue(error.contains("\"executed\":false"));
    assertEquals("bad", feedback.getResponses().getFirst().id());
    assertFalse(result.returnDirect());
    assertEquals(1, context.budgetSnapshot().argumentCorrections());
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(search, fetch);
    assertEquals(
        List.of(AgentEventType.TOOL_FAILED), events.stream().map(AgentEvent::type).toList());

    when(search.search(anyString(), anyInt())).thenReturn(List.of());
    // 同じ実行の予算と履歴を引き継ぎ、引数を直した要求だけを再び実行する。
    var fixed =
        loop.executeResumable(
            new Prompt(result.conversationHistory(), prompt.getOptions()),
            response("fixed", "\"maxResults\":5,\"readTop\":0"),
            context);
    String success =
        ((ToolResponseMessage) fixed.conversationHistory().getLast())
            .getResponses()
            .getFirst()
            .responseData();
    assertFalse(ProgressEvaluator.failedResult(success));
    org.mockito.Mockito.verify(search).search("Void full-stack framework", 5);
    verifyNoInteractions(fetch);
    assertEquals(
        List.of(
            AgentEventType.TOOL_FAILED, AgentEventType.TOOL_STARTED, AgentEventType.TOOL_COMPLETED),
        events.stream().map(AgentEvent::type).toList());
    assertEquals(1, context.budgetSnapshot().argumentCorrections());
  }

  /** 検索処理の障害を引数不足と混同せず、引数修正の回数を消費しないことを確認する。 */
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void searchFailuresStillPropagateRatherThanRequestArgumentRepair(boolean guarded)
      throws Exception {
    var context = context(guarded);
    var prompt = prompt(context);
    when(search.search(anyString(), anyInt()))
        .thenThrow(new IllegalStateException("search unavailable"));
    assertThrows(
        RuntimeException.class,
        () ->
            loop.executeResumable(
                prompt, response("valid", "\"maxResults\":5,\"readTop\":0"), context));
    assertEquals(0, context.budgetSnapshot().argumentCorrections());
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(fetch);
    assertEquals(
        List.of(AgentEventType.TOOL_STARTED, AgentEventType.TOOL_FAILED),
        events.stream().map(AgentEvent::type).toList());
  }

  /** 会話の実行で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void repeatedBadArgumentsRespectExistingRepairBudget(boolean guarded) {
    var context = context(guarded);
    var prompt = prompt(context);
    var bad = response("bad", "\"maxResults\":10");
    for (int i = 0; i < OutputLimitRunBudget.DEFAULT_MAX_ARGUMENT_CORRECTIONS; i++)
      loop.executeResumable(prompt, bad, context);
    var stopped =
        assertThrows(
            ExecutionStoppedException.class, () -> loop.executeResumable(prompt, bad, context));
    assertEquals(ExecutionStoppedException.Reason.TOOL_ARGUMENT_REPAIR_EXHAUSTED, stopped.reason());
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(search, fetch);
  }
}
