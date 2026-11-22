package dev.mikoto2000.rei.core.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.bluesky.BlueskyPostTools;
import dev.mikoto2000.rei.briefing.BriefingTools;
import dev.mikoto2000.rei.chatmemory.ConversationMemory;
import dev.mikoto2000.rei.chatmemory.WindowedConversationMemory;
import dev.mikoto2000.rei.conversation.ConversationHistoryTools;
import dev.mikoto2000.rei.conversation.ConversationTurnStore;
import dev.mikoto2000.rei.core.Tools;
import dev.mikoto2000.rei.core.actionplan.ActionPlanAdvisor;
import dev.mikoto2000.rei.core.checkpoint.CheckpointAdvisor;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.RunScopedAdvisor;
import dev.mikoto2000.rei.core.contextbudget.ContextAssembler;
import dev.mikoto2000.rei.core.contextbudget.ContextCompressionProperties;
import dev.mikoto2000.rei.core.contextbudget.ContextHistoryAdvisor;
import dev.mikoto2000.rei.core.contextbudget.ConversationCompressor;
import dev.mikoto2000.rei.core.contextbudget.ConversationSummaryRepository;
import dev.mikoto2000.rei.core.contextbudget.RawToolResultStore;
import dev.mikoto2000.rei.core.contextbudget.TokenEstimator;
import dev.mikoto2000.rei.core.contextbudget.ToolResultCompressor;
import dev.mikoto2000.rei.core.filesummary.FileSummaryAdvisor;
import dev.mikoto2000.rei.core.recentchanges.RecentChangesAdvisor;
import dev.mikoto2000.rei.core.relatedgraph.RelatedFileGraphAdvisor;
import dev.mikoto2000.rei.core.stagnation.StagnationAdvisor;
import dev.mikoto2000.rei.core.stagnation.ProgressEvaluator;
import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;
import dev.mikoto2000.rei.core.taskstate.TaskStateAdvisor;
import dev.mikoto2000.rei.core.taskstate.TaskStateTools;
import dev.mikoto2000.rei.core.working.WorkingSetAdvisor;
import dev.mikoto2000.rei.event.ToolEventCallbackProvider;
import dev.mikoto2000.rei.feed.FeedTools;
import dev.mikoto2000.rei.googlecalendar.GoogleCalendarTools;
import dev.mikoto2000.rei.llm.LlmProperties;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import dev.mikoto2000.rei.reminder.ReminderTools;
import dev.mikoto2000.rei.search.SearchTools;
import dev.mikoto2000.rei.skills.AgentSkillAdvisor;
import dev.mikoto2000.rei.sound.SoundNotificationTools;
import dev.mikoto2000.rei.task.TaskTools;
import dev.mikoto2000.rei.temporal.ClockTools;
import dev.mikoto2000.rei.temporal.RuntimeContextAdvisor;
import dev.mikoto2000.rei.temporal.SchedulerTools;
import dev.mikoto2000.rei.text.TextTools;
import dev.mikoto2000.rei.urlfetch.UrlContentFetchTools;
import dev.mikoto2000.rei.websearch.WebSearchTools;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.api.MemoryAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;

/** AI機能の構成の結果と状態の契約を検証するテスト。 */
class AiConfigurationTest {

  @Test
  @Tag("integration")
  void disabledCompressionStillFiltersFailedHistoryThroughConfiguredClient(@TempDir Path root)
      throws Exception {
    var turns = ConversationTurnStore.inMemory();
    var completed = new AgentRunContext("completed", "chat", root);
    var failed = new AgentRunContext("failed", "chat", root);
    var current = new AgentRunContext("current", "chat", root);
    turns.start(completed, "completed configuration request");
    turns.finish(completed, ConversationTurnStore.Status.COMPLETED, "verified completed response");
    turns.start(failed, "ARCHIVED-FAILED-TASK");
    turns.finish(failed, ConversationTurnStore.Status.FAILED, "ABANDONED-DRAFT");
    turns.start(current, "current independent question");
    var savedTurns = turns.read("chat");
    var memory = WindowedConversationMemory.builder().build();
    memory.add("chat", List.of(
        new UserMessage("ARCHIVED-FAILED-TASK"),
        new AssistantMessage("ABANDONED-DRAFT; LEGACY-POLLUTED-NOTE")));
    var properties = new ContextCompressionProperties();
    properties.setEnabled(false);
    var summaries = new ConversationSummaryRepository(root);
    var compressor = Mockito.mock(ConversationCompressor.class);
    var estimator = TokenEstimator.conservative();
    var assembler = new ContextAssembler(
        properties, estimator, summaries,
        new ToolResultCompressor(new RawToolResultStore(root), estimator, 8000, 2000),
        compressor, null, null);
    var history = new ContextHistoryAdvisor(turns, memory, summaries);
    var sent = new AtomicReference<Prompt>();
    var model = Mockito.mock(ChatModel.class);
    when(model.getOptions()).thenReturn(OpenAiChatOptions.builder().build());
    when(model.stream(Mockito.any(Prompt.class))).thenAnswer(invocation -> {
      sent.set(invocation.getArgument(0));
      return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("current answer")))));
    });
    var configuration = subAgentConfiguration(model, memory);
    configuration.setContextCompression(assembler, history, null, properties);
    var client = configuration.chatClient();
    // Keep unrelated configured advisors inert while retaining the actual history/model chain.
    for (var advisor : getDefaultAdvisors(client)) {
      if (!(advisor instanceof RunScopedAdvisor scoped) || scoped.delegate() == history) continue;
      var delegate = scoped.delegate();
      if (!Mockito.mockingDetails(delegate).isMock()) continue;
      when(delegate.getName()).thenReturn(delegate.getClass().getSimpleName());
      when(delegate.getScheduler()).thenReturn(BaseAdvisor.DEFAULT_SCHEDULER);
      when(delegate.before(Mockito.any(), Mockito.any())).thenAnswer(invocation -> invocation.getArgument(0));
      when(delegate.after(Mockito.any(), Mockito.any())).thenAnswer(invocation -> invocation.getArgument(0));
    }
    var execution = new RunExecutionContext(
        "current", new OutputLimitRunBudget(0, 10), new ProgressEvaluator(root),
        new dev.mikoto2000.rei.event.AgentEventFactory(java.time.Clock.systemUTC()), event -> {});
    execution.setRunContext(current);
    var options = OpenAiChatOptions.builder()
        .toolContext(Map.of(RunExecutionContext.KEY, execution)).build();
    var response = client.prompt(new Prompt(new UserMessage("current independent question"), options))
        .advisors(a -> a.param(AgentRunContext.class.getName(), current)
            .param(MemoryAdvisor.CONVERSATION_ID, "chat"))
        .stream().chatResponse().blockLast();

    assertEquals("current answer", response.getResult().getOutput().getText());
    org.assertj.core.api.Assertions.assertThat(sent.get().getContents())
        .contains("completed configuration request", "verified completed response", "current independent question")
        .doesNotContain("ARCHIVED-FAILED-TASK", "ABANDONED-DRAFT", "LEGACY-POLLUTED-NOTE");
    assertEquals("current independent question", sent.get().getInstructions().getLast().getText());
    org.assertj.core.api.Assertions.assertThat(turns.read("chat")).containsExactlyElementsOf(savedTurns);
    org.assertj.core.api.Assertions.assertThat(memory.get("chat"))
        .extracting(org.springframework.ai.chat.messages.Message::getText)
        .contains("ARCHIVED-FAILED-TASK", "ABANDONED-DRAFT; LEGACY-POLLUTED-NOTE");
    Mockito.verify(model, Mockito.times(1)).stream(Mockito.any(Prompt.class));
    Mockito.verifyNoInteractions(compressor);
  }

  @Test
  void exposesHolidayToolsInDefaultClient() throws Exception {
    var configuration = subAgentConfiguration();
    var holiday =
        new dev.mikoto2000.rei.temporal.holiday.JapaneseHolidayTools(
            Mockito.mock(dev.mikoto2000.rei.temporal.holiday.JapaneseHolidayService.class));
    configuration.setHolidayTools(mockProviderReturning(holiday));
    Object request = getDefaultChatClientRequest(configuration.chatClient());
    Field field = request.getClass().getDeclaredField("toolCallbacks");
    field.setAccessible(true);
    var names =
        ((List<?>) field.get(request))
            .stream()
                .map(org.springframework.ai.tool.ToolCallback.class::cast)
                .map(callback -> callback.getToolDefinition().name())
                .toList();
    org.assertj.core.api.Assertions.assertThat(names).contains("getHolidayInfo", "listHolidays");
  }

  /** 機能の組み立てで、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
    "false,false,false,false,delegateTask|delegateTasks",
    "false,true,true,true,delegateTask|delegateTasks",
    "true,false,false,false,delegateTask|delegateTasks|listDurableSubAgents|getDurableSubAgent|resumeSubAgent|reconcileSubAgent",
    "true,false,false,true,delegateTask|delegateTasks|listDurableSubAgents|getDurableSubAgent|resumeSubAgent|reconcileSubAgent",
    "true,true,false,false,delegateTask|delegateTasks|listDurableSubAgents|getDurableSubAgent|resumeSubAgent|reconcileSubAgent|delegateTaskGraph|getSubAgentGraph|resumeSubAgentGraph",
    "true,false,true,false,delegateTask|delegateTasks|listDurableSubAgents|getDurableSubAgent|resumeSubAgent|reconcileSubAgent|compareSubAgentAnswers",
    "true,false,true,true,delegateTask|delegateTasks|listDurableSubAgents|getDurableSubAgent|resumeSubAgent|reconcileSubAgent|compareSubAgentAnswers|judgeSubAgentAnswers",
    "true,true,true,true,delegateTask|delegateTasks|listDurableSubAgents|getDurableSubAgent|resumeSubAgent|reconcileSubAgent|delegateTaskGraph|getSubAgentGraph|resumeSubAgentGraph|compareSubAgentAnswers|judgeSubAgentAnswers"
  })
  void chatClientOffersOnlyEnabledSubAgentToolsWithoutResolvingRunnerDuringConstruction(
      boolean durable, boolean dag, boolean consensus, boolean judge, String expectedNames)
      throws Exception {
    var configuration = subAgentConfiguration();
    var settings = new dev.mikoto2000.rei.subagent.SubAgentProperties();
    settings.setDurableEnabled(durable);
    settings.setDagEnabled(dag);
    settings.setConsensusEnabled(consensus);
    settings.setConsensusJudgeEnabled(judge);
    configuration.setSubAgentProperties(settings);
    var tools = Mockito.mock(dev.mikoto2000.rei.subagent.SubAgentTools.class);
    when(tools.callback(Mockito.anyString()))
        .thenAnswer(invocation -> namedCallback(invocation.getArgument(0)));
    ObjectProvider<dev.mikoto2000.rei.subagent.SubAgentTools> toolsProvider =
        mockProviderReturning(tools);
    when(toolsProvider.getObject()).thenReturn(tools);
    configuration.setSubAgentTools(toolsProvider);

    Object request = getDefaultChatClientRequest(configuration.chatClient());
    Mockito.verify(toolsProvider, Mockito.never()).getObject();
    Field callbacksField = request.getClass().getDeclaredField("toolCallbacks");
    callbacksField.setAccessible(true);
    var subAgentNames =
        java.util.Set.of(
            "delegateTask",
            "delegateTasks",
            "listDurableSubAgents",
            "getDurableSubAgent",
            "resumeSubAgent",
            "reconcileSubAgent",
            "delegateTaskGraph",
            "getSubAgentGraph",
            "resumeSubAgentGraph",
            "compareSubAgentAnswers",
            "judgeSubAgentAnswers");
    var callbacks =
        ((List<?>) callbacksField.get(request))
            .stream()
                .map(org.springframework.ai.tool.ToolCallback.class::cast)
                .filter(callback -> subAgentNames.contains(callback.getToolDefinition().name()))
                .toList();
    org.assertj.core.api.Assertions.assertThat(callbacks)
        .allMatch(dev.mikoto2000.rei.subagent.LazyDelegationCallback.class::isInstance);
    org.assertj.core.api.Assertions.assertThat(
            callbacks.stream().map(callback -> callback.getToolDefinition().name()))
        .containsExactly(expectedNames.split("\\|"));
  }

  /** {@code subAgentConfiguration} の条件を再現するための補助情報を返す。 */
  private AiConfiguration subAgentConfiguration() {
    return subAgentConfiguration(Mockito.mock(ChatModel.class), Mockito.mock(ConversationMemory.class));
  }

  private AiConfiguration subAgentConfiguration(ChatModel model, ConversationMemory memory) {
    return new AiConfiguration(
        systemPromptService(),
        model,
        memory,
        new Tools(),
        Mockito.mock(GoogleCalendarTools.class),
        Mockito.mock(TaskTools.class),
        Mockito.mock(BriefingTools.class),
        Mockito.mock(FeedTools.class),
        Mockito.mock(ReminderTools.class),
        Mockito.mock(SearchTools.class),
        Mockito.mock(WebSearchTools.class),
        Mockito.mock(SoundNotificationTools.class),
        Mockito.mock(BlueskyPostTools.class),
        Mockito.mock(dev.mikoto2000.rei.summarize.SummaryTools.class),
        Mockito.mock(UrlContentFetchTools.class),
        Mockito.mock(TextTools.class),
        Mockito.mock(ClockTools.class),
        Mockito.mock(SchedulerTools.class),
        Mockito.mock(ConversationHistoryTools.class),
        Mockito.mock(RuntimeContextAdvisor.class),
        Mockito.mock(WorkingSetAdvisor.class),
        Mockito.mock(TaskStateAdvisor.class),
        Mockito.mock(RecentChangesAdvisor.class),
        Mockito.mock(FileSummaryAdvisor.class),
        Mockito.mock(RelatedFileGraphAdvisor.class),
        Mockito.mock(CheckpointAdvisor.class),
        Mockito.mock(ActionPlanAdvisor.class),
        Mockito.mock(StagnationAdvisor.class),
        Mockito.mock(TaskStateTools.class),
        new LlmProperties(),
        mockProviderReturning(null),
        mockProviderReturning(null));
  }

  /** {@code namedCallback} の条件を再現するための補助情報を返す。 */
  private org.springframework.ai.tool.ToolCallback namedCallback(String name) {
    var callback = Mockito.mock(org.springframework.ai.tool.ToolCallback.class);
    when(callback.getToolDefinition())
        .thenReturn(
            org.springframework.ai.tool.definition.ToolDefinition.builder()
                .name(name)
                .description(name)
                .inputSchema("{}")
                .build());
    return callback;
  }

  /** 機能の組み立てで、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void chatClientIncludesMcpToolCallbackProviderWhenAvailable() throws Exception {
    ToolEventCallbackProvider mcpToolCallbackProvider =
        Mockito.mock(ToolEventCallbackProvider.class);
    ObjectProvider<ToolEventCallbackProvider> provider =
        mockProviderReturning(mcpToolCallbackProvider);

    AiConfiguration configuration =
        new AiConfiguration(
            systemPromptService(),
            Mockito.mock(ChatModel.class),
            Mockito.mock(ConversationMemory.class),
            new Tools(),
            Mockito.mock(GoogleCalendarTools.class),
            Mockito.mock(TaskTools.class),
            Mockito.mock(BriefingTools.class),
            Mockito.mock(FeedTools.class),
            Mockito.mock(ReminderTools.class),
            Mockito.mock(SearchTools.class),
            Mockito.mock(WebSearchTools.class),
            Mockito.mock(SoundNotificationTools.class),
            Mockito.mock(BlueskyPostTools.class),
            Mockito.mock(dev.mikoto2000.rei.summarize.SummaryTools.class),
            Mockito.mock(UrlContentFetchTools.class),
            Mockito.mock(TextTools.class),
            Mockito.mock(ClockTools.class),
            Mockito.mock(SchedulerTools.class),
            Mockito.mock(ConversationHistoryTools.class),
            Mockito.mock(RuntimeContextAdvisor.class),
            Mockito.mock(WorkingSetAdvisor.class),
            Mockito.mock(TaskStateAdvisor.class),
            Mockito.mock(RecentChangesAdvisor.class),
            Mockito.mock(FileSummaryAdvisor.class),
            Mockito.mock(RelatedFileGraphAdvisor.class),
            Mockito.mock(CheckpointAdvisor.class),
            Mockito.mock(ActionPlanAdvisor.class),
            Mockito.mock(StagnationAdvisor.class),
            Mockito.mock(TaskStateTools.class),
            new LlmProperties(),
            mockProviderReturning(null),
            provider);

    var external = Mockito.mock(dev.mikoto2000.rei.externalagent.ExternalAgentTools.class);
    var first = Mockito.mock(org.springframework.ai.tool.ToolCallback.class);
    var second = Mockito.mock(org.springframework.ai.tool.ToolCallback.class);
    when(first.getToolDefinition())
        .thenReturn(
            org.springframework.ai.tool.definition.ToolDefinition.builder()
                .name("firstExternal")
                .description("first")
                .inputSchema("{}")
                .build());
    when(second.getToolDefinition())
        .thenReturn(
            org.springframework.ai.tool.definition.ToolDefinition.builder()
                .name("secondExternal")
                .description("second")
                .inputSchema("{}")
                .build());
    when(external.callbacks())
        .thenReturn(new org.springframework.ai.tool.ToolCallback[] {first, second});
    configuration.setExternalAgentTools(external, null);
    ChatClient chatClient = configuration.chatClient();
    Object defaultRequest = getDefaultChatClientRequest(chatClient);
    Field directCallbacks = defaultRequest.getClass().getDeclaredField("toolCallbacks");
    directCallbacks.setAccessible(true);
    var registered = (List<?>) directCallbacks.get(defaultRequest);
    assertEquals(
        List.of(first, second), registered.subList(registered.size() - 2, registered.size()));

    List<?> toolCallbackProviders = getDefaultToolCallbackProviders(chatClient);
    assertEquals(1, toolCallbackProviders.size());
    assertSame(mcpToolCallbackProvider, toolCallbackProviders.getFirst());

    var computerTools =
        new dev.mikoto2000.rei.computeruse.ComputerUseTools(
            Mockito.mock(dev.mikoto2000.rei.computeruse.ComputerUseService.class),
            new dev.mikoto2000.rei.core.service.CommandCancellationService(),
            new dev.mikoto2000.rei.event.AgentEventFactory(java.time.Clock.systemUTC()),
            event -> {});
    configuration.setComputerUseTools(mockProviderReturning(computerTools));
    Object request = getDefaultChatClientRequest(configuration.chatClient());
    Field callbacksField = request.getClass().getDeclaredField("toolCallbacks");
    callbacksField.setAccessible(true);
    var callbacks = (List<?>) callbacksField.get(request);
    assertEquals(
        1,
        callbacks.stream()
            .map(org.springframework.ai.tool.ToolCallback.class::cast)
            .filter(callback -> callback.getToolDefinition().name().equals("computerUse"))
            .count());
  }

  /** 機能の組み立てで、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void chatClientOmitsMcpToolCallbackProviderWhenUnavailable() throws Exception {
    ObjectProvider<ToolEventCallbackProvider> provider = mockProviderReturning(null);

    AiConfiguration configuration =
        new AiConfiguration(
            systemPromptService(),
            Mockito.mock(ChatModel.class),
            Mockito.mock(ConversationMemory.class),
            new Tools(),
            Mockito.mock(GoogleCalendarTools.class),
            Mockito.mock(TaskTools.class),
            Mockito.mock(BriefingTools.class),
            Mockito.mock(FeedTools.class),
            Mockito.mock(ReminderTools.class),
            Mockito.mock(SearchTools.class),
            Mockito.mock(WebSearchTools.class),
            Mockito.mock(SoundNotificationTools.class),
            Mockito.mock(BlueskyPostTools.class),
            Mockito.mock(dev.mikoto2000.rei.summarize.SummaryTools.class),
            Mockito.mock(UrlContentFetchTools.class),
            Mockito.mock(TextTools.class),
            Mockito.mock(ClockTools.class),
            Mockito.mock(SchedulerTools.class),
            Mockito.mock(ConversationHistoryTools.class),
            Mockito.mock(RuntimeContextAdvisor.class),
            Mockito.mock(WorkingSetAdvisor.class),
            Mockito.mock(TaskStateAdvisor.class),
            Mockito.mock(RecentChangesAdvisor.class),
            Mockito.mock(FileSummaryAdvisor.class),
            Mockito.mock(RelatedFileGraphAdvisor.class),
            Mockito.mock(CheckpointAdvisor.class),
            Mockito.mock(ActionPlanAdvisor.class),
            Mockito.mock(StagnationAdvisor.class),
            Mockito.mock(TaskStateTools.class),
            new LlmProperties(),
            mockProviderReturning(null),
            provider);

    ChatClient chatClient = configuration.chatClient();

    List<?> toolCallbackProviders = getDefaultToolCallbackProviders(chatClient);
    assertEquals(0, toolCallbackProviders.size());
  }

  /** 機能の組み立てで、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void chatClientIncludesAgentSkillAdvisorWhenAvailable() throws Exception {
    AgentSkillAdvisor agentSkillAdvisor = Mockito.mock(AgentSkillAdvisor.class);

    AiConfiguration configuration =
        new AiConfiguration(
            systemPromptService(),
            Mockito.mock(ChatModel.class),
            Mockito.mock(ConversationMemory.class),
            new Tools(),
            Mockito.mock(GoogleCalendarTools.class),
            Mockito.mock(TaskTools.class),
            Mockito.mock(BriefingTools.class),
            Mockito.mock(FeedTools.class),
            Mockito.mock(ReminderTools.class),
            Mockito.mock(SearchTools.class),
            Mockito.mock(WebSearchTools.class),
            Mockito.mock(SoundNotificationTools.class),
            Mockito.mock(BlueskyPostTools.class),
            Mockito.mock(dev.mikoto2000.rei.summarize.SummaryTools.class),
            Mockito.mock(UrlContentFetchTools.class),
            Mockito.mock(TextTools.class),
            Mockito.mock(ClockTools.class),
            Mockito.mock(SchedulerTools.class),
            Mockito.mock(ConversationHistoryTools.class),
            Mockito.mock(RuntimeContextAdvisor.class),
            Mockito.mock(WorkingSetAdvisor.class),
            Mockito.mock(TaskStateAdvisor.class),
            Mockito.mock(RecentChangesAdvisor.class),
            Mockito.mock(FileSummaryAdvisor.class),
            Mockito.mock(RelatedFileGraphAdvisor.class),
            Mockito.mock(CheckpointAdvisor.class),
            Mockito.mock(ActionPlanAdvisor.class),
            Mockito.mock(StagnationAdvisor.class),
            Mockito.mock(TaskStateTools.class),
            new LlmProperties(),
            mockProviderReturning(agentSkillAdvisor),
            mockProviderReturning(null));

    ChatClient chatClient = configuration.chatClient();

    List<?> advisors = getDefaultAdvisors(chatClient);
    assertSame(
        agentSkillAdvisor,
        ((dev.mikoto2000.rei.core.chat.RunScopedAdvisor) advisors.getLast()).delegate());
  }

  /** モデルへ対話を依頼する窓口について、設定済み最大出力トークン数形式既定選択項目を利用することを確認する。 */
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void chatClientUsesConfiguredMaxOutputTokensAsDefaultOption(boolean completionTokens)
      throws Exception {
    LlmProperties llmProperties = new LlmProperties();
    llmProperties.setMaxOutputTokens(4096);
    ChatModel model = Mockito.mock(ChatModel.class);
    when(model.getOptions())
        .thenReturn(
            completionTokens
                ? org.springframework.ai.openai.OpenAiChatOptions.builder()
                    .model("configured")
                    .maxCompletionTokens(8192)
                    .build()
                : org.springframework.ai.openai.OpenAiChatOptions.builder()
                    .model("configured")
                    .maxTokens(8192)
                    .build());

    AiConfiguration configuration =
        new AiConfiguration(
            systemPromptService(),
            model,
            Mockito.mock(ConversationMemory.class),
            new Tools(),
            Mockito.mock(GoogleCalendarTools.class),
            Mockito.mock(TaskTools.class),
            Mockito.mock(BriefingTools.class),
            Mockito.mock(FeedTools.class),
            Mockito.mock(ReminderTools.class),
            Mockito.mock(SearchTools.class),
            Mockito.mock(WebSearchTools.class),
            Mockito.mock(SoundNotificationTools.class),
            Mockito.mock(BlueskyPostTools.class),
            Mockito.mock(dev.mikoto2000.rei.summarize.SummaryTools.class),
            Mockito.mock(UrlContentFetchTools.class),
            Mockito.mock(TextTools.class),
            Mockito.mock(ClockTools.class),
            Mockito.mock(SchedulerTools.class),
            Mockito.mock(ConversationHistoryTools.class),
            Mockito.mock(RuntimeContextAdvisor.class),
            Mockito.mock(WorkingSetAdvisor.class),
            Mockito.mock(TaskStateAdvisor.class),
            Mockito.mock(RecentChangesAdvisor.class),
            Mockito.mock(FileSummaryAdvisor.class),
            Mockito.mock(RelatedFileGraphAdvisor.class),
            Mockito.mock(CheckpointAdvisor.class),
            Mockito.mock(ActionPlanAdvisor.class),
            Mockito.mock(StagnationAdvisor.class),
            Mockito.mock(TaskStateTools.class),
            llmProperties,
            mockProviderReturning(null),
            mockProviderReturning(null));

    ChatOptions options = getDefaultChatOptions(configuration.chatClient());

    assertEquals(completionTokens ? null : 4096, options.getMaxTokens());
    assertEquals(
        completionTokens ? 4096 : null,
        ((org.springframework.ai.openai.OpenAiChatOptions) options).getMaxCompletionTokens());
    assertEquals(
        1,
        getDefaultAdvisors(configuration.chatClient()).stream()
            .filter(dev.mikoto2000.rei.llm.RunAwareToolCallingAdvisor.class::isInstance)
            .count());
  }

  /** 検証用に、機能の組み立てで参照する既定ツール完了時の処理提供元一覧を返す。 */
  private List<?> getDefaultToolCallbackProviders(ChatClient chatClient) throws Exception {
    Object defaultRequest = getDefaultChatClientRequest(chatClient);

    Field toolCallbackProvidersField =
        defaultRequest.getClass().getDeclaredField("toolCallbackProviders");
    toolCallbackProvidersField.setAccessible(true);
    return (List<?>) toolCallbackProvidersField.get(defaultRequest);
  }

  /** 検証用に、機能の組み立てで参照する既定Advisorsを返す。 */
  private List<?> getDefaultAdvisors(ChatClient chatClient) throws Exception {
    Object defaultRequest = getDefaultChatClientRequest(chatClient);

    Field advisorsField = defaultRequest.getClass().getDeclaredField("advisors");
    advisorsField.setAccessible(true);
    return (List<?>) advisorsField.get(defaultRequest);
  }

  /** 検証用に、機能の組み立てで参照する既定チャット選択可能な設定を返す。 */
  private ChatOptions getDefaultChatOptions(ChatClient chatClient) throws Exception {
    Object defaultRequest = getDefaultChatClientRequest(chatClient);
    Field chatOptionsField = defaultRequest.getClass().getDeclaredField("optionsCustomizer");
    chatOptionsField.setAccessible(true);
    return ((ChatOptions.Builder<?>) chatOptionsField.get(defaultRequest)).build();
  }

  /** 検証用に、機能の組み立てで参照する既定モデルへ対話を依頼する窓口要求を返す。 */
  private Object getDefaultChatClientRequest(ChatClient chatClient) throws Exception {
    Field defaultRequestField = chatClient.getClass().getDeclaredField("defaultChatClientRequest");
    defaultRequestField.setAccessible(true);
    return defaultRequestField.get(chatClient);
  }

  /** {@code mockProviderReturning} の条件を再現するための補助情報を返す。 */
  private <T> ObjectProvider<T> mockProviderReturning(T value) {
    ObjectProvider<T> provider = Mockito.mock();
    when(provider.getIfAvailable()).thenReturn(value);
    return provider;
  }

  /** {@code systemPromptService} の条件を再現するための補助情報を返す。 */
  private SystemPromptService systemPromptService() {
    SystemPromptService service = Mockito.mock(SystemPromptService.class);
    when(service.systemPrompt()).thenReturn("system prompt");
    return service;
  }
}
