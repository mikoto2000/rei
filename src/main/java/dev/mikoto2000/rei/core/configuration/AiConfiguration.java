package dev.mikoto2000.rei.core.configuration;

import dev.mikoto2000.rei.bluesky.BlueskyPostTools;
import dev.mikoto2000.rei.bluesky.BlueskyProperties;
import dev.mikoto2000.rei.briefing.BriefingTools;
import dev.mikoto2000.rei.conversation.ConversationHistoryTools;
import dev.mikoto2000.rei.core.Tools;
import dev.mikoto2000.rei.core.actionplan.ActionPlanAdvisor;
import dev.mikoto2000.rei.core.checkpoint.CheckpointAdvisor;
import dev.mikoto2000.rei.core.filesummary.FileSummaryAdvisor;
import dev.mikoto2000.rei.core.recentchanges.RecentChangesAdvisor;
import dev.mikoto2000.rei.core.relatedgraph.RelatedFileGraphAdvisor;
import dev.mikoto2000.rei.core.stagnation.StagnationAdvisor;
import dev.mikoto2000.rei.core.taskstate.TaskStateAdvisor;
import dev.mikoto2000.rei.core.taskstate.TaskStateTools;
import dev.mikoto2000.rei.core.working.WorkingSetAdvisor;
import dev.mikoto2000.rei.event.ToolEventCallbackProvider;
import dev.mikoto2000.rei.feed.FeedProperties;
import dev.mikoto2000.rei.feed.FeedTools;
import dev.mikoto2000.rei.googlecalendar.GoogleCalendarProperties;
import dev.mikoto2000.rei.googlecalendar.GoogleCalendarTools;
import dev.mikoto2000.rei.image.ImageProperties;
import dev.mikoto2000.rei.interest.InterestProperties;
import dev.mikoto2000.rei.llm.LlmProperties;
import dev.mikoto2000.rei.reminder.ReminderTools;
import dev.mikoto2000.rei.search.SearchTools;
import dev.mikoto2000.rei.skills.AgentSkillAdvisor;
import dev.mikoto2000.rei.skills.AgentSkillsProperties;
import dev.mikoto2000.rei.sound.SoundNotificationTools;
import dev.mikoto2000.rei.task.TaskTools;
import dev.mikoto2000.rei.temporal.ClockTools;
import dev.mikoto2000.rei.temporal.RuntimeContextAdvisor;
import dev.mikoto2000.rei.temporal.SchedulerTools;
import dev.mikoto2000.rei.text.TextTools;
import dev.mikoto2000.rei.urlfetch.UrlContentFetchTools;
import dev.mikoto2000.rei.vectordocument.VectorDocumentProperties;
import dev.mikoto2000.rei.websearch.WebSearchProperties;
import dev.mikoto2000.rei.websearch.WebSearchTools;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** AIの構成要素を接続し、設定に従って利用できるようにする。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
  CoreProperties.class,
  GoogleCalendarProperties.class,
  WebSearchProperties.class,
  VectorDocumentProperties.class,
  SqliteVecProperties.class,
  InterestProperties.class,
  FeedProperties.class,
  BlueskyProperties.class,
  AgentSkillsProperties.class,
  LlmProperties.class,
  ImageProperties.class
})
@RequiredArgsConstructor
public class AiConfiguration {
  /** Structured HTTP tools share the existing callback and permission boundaries. */
  private dev.mikoto2000.rei.httpapi.HttpApiTools httpApiTools;

  /** Register API tools without changing the legacy constructor contract. */
  @org.springframework.beans.factory.annotation.Autowired
  void setHttpApiTools(dev.mikoto2000.rei.httpapi.HttpApiTools tools) {
    this.httpApiTools = tools;
  }
  /** 依存条件ツール一覧。 */
  private dev.mikoto2000.rei.core.dependency.DependencyTools dependencyTools;

  /** 機能の組み立てで使用する依存条件ツール一覧を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  void setDependencyTools(dev.mikoto2000.rei.core.dependency.DependencyTools tools) {
    this.dependencyTools = tools;
  }

  /** ファイル依存条件ツール一覧。 */
  private dev.mikoto2000.rei.core.FileDependencyTools fileDependencyTools;

  /** 機能の組み立てで使用するファイル依存条件ツール一覧を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  void setFileDependencyTools(dev.mikoto2000.rei.core.FileDependencyTools tools) {
    this.fileDependencyTools = tools;
  }

  /** プロセス依存条件ツール一覧。 */
  private dev.mikoto2000.rei.core.ProcessDependencyTools processDependencyTools;

  /** 機能の組み立てで使用するプロセス依存条件ツール一覧を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  void setProcessDependencyTools(dev.mikoto2000.rei.core.ProcessDependencyTools tools) {
    this.processDependencyTools = tools;
  }

  /** 実行状況の通知を生成する窓口。 */
  private dev.mikoto2000.rei.event.AgentEventFactory eventFactory;

  /** 実行状況の通知先。 */
  private dev.mikoto2000.rei.event.AgentEventPublisher eventPublisher;

  /** 機能の組み立てで使用する作業イベント一覧を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired
  void setWorkEvents(
      dev.mikoto2000.rei.event.AgentEventFactory factory,
      dev.mikoto2000.rei.event.AgentEventPublisher publisher) {
    eventFactory = factory;
    eventPublisher = publisher;
  }

  /** 作業文脈。 */
  private ObjectProvider<dev.mikoto2000.rei.workcontext.WorkContextAdvisor> workContext;

  /** 作業ツール一覧。 */
  private ObjectProvider<dev.mikoto2000.rei.workcontext.WorkContextTools> workTools;

  /** 経験の記録ツール一覧。 */
  private ObjectProvider<dev.mikoto2000.rei.episode.EpisodeTools> episodeTools;

  /** 機能の組み立てで使用する経験の記録ツール一覧を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired
  void setEpisodeTools(ObjectProvider<dev.mikoto2000.rei.episode.EpisodeTools> tools) {
    episodeTools = tools;
  }

  /** 再開文脈。 */
  private ObjectProvider<dev.mikoto2000.rei.checkpoint.ResumeContextAdvisor> resumeContext;

  /** 再開用の記録ツール一覧。 */
  private ObjectProvider<dev.mikoto2000.rei.checkpoint.CheckpointTools> checkpointTools;

  /** 機能の組み立てで使用する再開用の記録を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired
  void setCheckpoint(
      ObjectProvider<dev.mikoto2000.rei.checkpoint.ResumeContextAdvisor> advisor,
      ObjectProvider<dev.mikoto2000.rei.checkpoint.CheckpointTools> tools) {
    resumeContext = advisor;
    checkpointTools = tools;
  }

  /** 機能の組み立てで使用する作業文脈を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired
  void setWorkContext(
      ObjectProvider<dev.mikoto2000.rei.workcontext.WorkContextAdvisor> advisor,
      ObjectProvider<dev.mikoto2000.rei.workcontext.WorkContextTools> tools) {
    workContext = advisor;
    workTools = tools;
  }

  /** 論文ツール一覧。 */
  private ObjectProvider<dev.mikoto2000.rei.paper.PaperTools> paperTools;

  /** 機能の組み立てで使用する論文ツール一覧を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired
  void setPaperTools(ObjectProvider<dev.mikoto2000.rei.paper.PaperTools> tools) {
    this.paperTools = tools;
  }

  /** 文脈文脈の組み立て。 */
  private dev.mikoto2000.rei.core.contextbudget.ContextAssembler contextAssembler;

  /** 文脈履歴。 */
  private dev.mikoto2000.rei.core.contextbudget.ContextHistoryAdvisor contextHistory;

  /** レビューツール一覧。 */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  private dev.mikoto2000.rei.review.ReviewTools reviewTools;

  /** 元の結果ツール一覧。 */
  private dev.mikoto2000.rei.core.contextbudget.RawToolResultTools rawResultTools;

  /** 機能の組み立てで使用する文脈圧縮を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  void setContextCompression(
      dev.mikoto2000.rei.core.contextbudget.ContextAssembler assembler,
      dev.mikoto2000.rei.core.contextbudget.ContextHistoryAdvisor history,
      dev.mikoto2000.rei.core.contextbudget.RawToolResultTools rawTools,
      dev.mikoto2000.rei.core.contextbudget.ContextCompressionProperties properties) {
    contextAssembler = assembler;
    rawResultTools = rawTools;
    if (properties.isEnabled()) contextHistory = history;
  }

  /** 状態表示ツール一覧。 */
  private ObjectProvider<dev.mikoto2000.rei.dot.DotTools> dotTools;

  /** 機能の組み立てで使用する状態表示ツール一覧を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired
  void setDotTools(ObjectProvider<dev.mikoto2000.rei.dot.DotTools> tools) {
    dotTools = tools;
  }

  /** 外部エージェントツール一覧。 */
  private dev.mikoto2000.rei.externalagent.ExternalAgentTools externalAgentTools;

  /** 外部委譲。 */
  private dev.mikoto2000.rei.externalagent.ExternalAgentDelegationService externalDelegation;

  /** 機能の組み立てで使用する外部エージェントツール一覧を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired
  void setExternalAgentTools(
      dev.mikoto2000.rei.externalagent.ExternalAgentTools tools,
      dev.mikoto2000.rei.externalagent.ExternalAgentDelegationService service) {
    this.externalAgentTools = tools;
    this.externalDelegation = service;
  }

  /** 画面操作利用ツール一覧。 */
  private ObjectProvider<dev.mikoto2000.rei.computeruse.ComputerUseTools> computerUseTools;

  /** 機能の組み立てで使用する画面操作利用ツール一覧を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired
  void setComputerUseTools(ObjectProvider<dev.mikoto2000.rei.computeruse.ComputerUseTools> tools) {
    this.computerUseTools = tools;
  }

  /** 補助エージェントツール一覧。 */
  private ObjectProvider<dev.mikoto2000.rei.subagent.SubAgentTools> subAgentTools;

  /** 補助エージェント設定。 */
  private dev.mikoto2000.rei.subagent.SubAgentProperties subAgentProperties =
      new dev.mikoto2000.rei.subagent.SubAgentProperties();

  /** 機能の組み立てで使用する補助エージェント設定を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setSubAgentProperties(dev.mikoto2000.rei.subagent.SubAgentProperties settings) {
    this.subAgentProperties = settings;
  }

  /** 機能の組み立てで使用する補助エージェントツール一覧を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired
  void setSubAgentTools(ObjectProvider<dev.mikoto2000.rei.subagent.SubAgentTools> tools) {
    this.subAgentTools = tools;
  }

  /** core設定。 */
  private final CoreProperties coreProperties;

  /** システムへの指示サービス。 */
  private final SystemPromptService systemPromptService;

  /** 応答生成に使うモデル。 */
  private final ChatModel chatModel;

  /** チャット記憶。 */
  private final ChatMemory chatMemory;

  /** ツール一覧。 */
  private final Tools tools;

  /** Google予定表ツール一覧。 */
  private final GoogleCalendarTools googleCalendarTools;

  /** タスクツール一覧。 */
  private final TaskTools taskTools;

  /** 日次案内ツール一覧。 */
  private final BriefingTools briefingTools;

  /** フィードツール一覧。 */
  private final FeedTools feedTools;

  /** リマインダーツール一覧。 */
  private final ReminderTools reminderTools;

  /** 検索ツール一覧。 */
  private final SearchTools searchTools;

  /** Web検索ツール一覧。 */
  private final WebSearchTools webSearchTools;

  /** 音通知通知ツール一覧。 */
  private final SoundNotificationTools soundNotificationTools;

  /** Bluesky投稿ツール一覧。 */
  private final BlueskyPostTools blueskyPostTools;

  /** 要約ツール一覧。 */
  private final dev.mikoto2000.rei.summarize.SummaryTools summaryTools;

  /** URL内容取得ツール一覧。 */
  private final UrlContentFetchTools urlContentFetchTools;

  /** テキストツール一覧。 */
  private final TextTools textTools;

  /** 時刻の取得元ツール一覧。 */
  private final ClockTools clockTools;

  /** 実行予定の管理ツール一覧。 */
  private final SchedulerTools schedulerTools;

  /** 会話履歴ツール一覧。 */
  private final ConversationHistoryTools conversationHistoryTools;

  /** 実行環境文脈対話の補助。 */
  private final RuntimeContextAdvisor runtimeContextAdvisor;

  /** 作業中集合対話の補助。 */
  private final WorkingSetAdvisor workingSetAdvisor;

  /** タスク状態対話の補助。 */
  private final TaskStateAdvisor taskStateAdvisor;

  /** 直近変更一覧対話の補助。 */
  private final RecentChangesAdvisor recentChangesAdvisor;

  /** ファイル要約対話の補助。 */
  private final FileSummaryAdvisor fileSummaryAdvisor;

  /** 関連ファイル関連図対話の補助。 */
  private final RelatedFileGraphAdvisor relatedFileGraphAdvisor;

  /** 再開用の記録対話の補助。 */
  private final CheckpointAdvisor checkpointAdvisor;

  /** 操作計画対話の補助。 */
  private final ActionPlanAdvisor actionPlanAdvisor;

  /** 停滞対話の補助。 */
  private final StagnationAdvisor stagnationAdvisor;

  /** タスク状態ツール一覧。 */
  private final TaskStateTools taskStateTools;

  /** 言語モデル設定。 */
  private final LlmProperties llmProperties;

  /** エージェントスキル対話の補助。 */
  private final ObjectProvider<AgentSkillAdvisor> agentSkillAdvisor;

  /** ツールイベント完了時の処理提供元。 */
  private final ObjectProvider<ToolEventCallbackProvider> toolEventCallbackProvider;

  /** 機能の組み立てで用いる既定チャット選択可能な設定を返す。 */
  private OpenAiChatOptions.Builder defaultChatOptions() {
    var options = OpenAiChatOptions.builder();
    if (chatModel.getOptions() instanceof OpenAiChatOptions defaults
        && defaults.getMaxCompletionTokens() != null)
      options.maxCompletionTokens(llmProperties.getMaxOutputTokens());
    else options.maxTokens(llmProperties.getMaxOutputTokens());
    return options;
  }

  /** 機能の組み立てで用いるモデルへ対話を依頼する窓口を返す。 */
  @Bean
  public ChatClient chatClient() {
    List<Advisor> advisors = new ArrayList<>();
    if (workContext != null && workContext.getIfAvailable() != null)
      advisors.add(workContext.getObject());
    if (contextHistory != null) advisors.add(contextHistory);
    else
      advisors.add(
          MessageChatMemoryAdvisor.builder(chatMemory)
              .scheduler(BaseAdvisor.DEFAULT_SCHEDULER)
              .build());
    advisors.add(runtimeContextAdvisor);
    advisors.add(workingSetAdvisor);
    advisors.add(taskStateAdvisor);
    advisors.add(recentChangesAdvisor);
    advisors.add(fileSummaryAdvisor);
    advisors.add(relatedFileGraphAdvisor);
    advisors.add(checkpointAdvisor);
    advisors.add(actionPlanAdvisor);
    advisors.add(stagnationAdvisor);
    if (externalDelegation != null)
      advisors.add(
          new dev.mikoto2000.rei.externalagent.ExternalAgentReviewAdvisor(
              externalDelegation, chatMemory));
    AgentSkillAdvisor skillAdvisor = agentSkillAdvisor.getIfAvailable();
    if (skillAdvisor != null) {
      advisors.add(skillAdvisor);
    }

    ChatClient.Builder builder =
        ChatClient.builder(
                new dev.mikoto2000.rei.core.stagnation.StagnationChatModel(
                    chatModel, contextAssembler))
            .defaultSystem(systemPromptService.systemPrompt())
            // Run-owned tools use Rei's loop; legacy non-run requests retain SDK tool handling.
            .defaultAdvisors(new dev.mikoto2000.rei.llm.RunAwareToolCallingAdvisor())
            .defaultOptions(defaultChatOptions())
            .defaultAdvisors(dev.mikoto2000.rei.core.chat.RunScopedAdvisor.wrap(advisors))
            .defaultTools(
                googleCalendarTools,
                taskTools,
                briefingTools,
                feedTools,
                reminderTools,
                searchTools,
                webSearchTools,
                soundNotificationTools,
                blueskyPostTools,
                urlContentFetchTools,
                textTools,
                clockTools,
                schedulerTools,
                taskStateTools,
                conversationHistoryTools,
                summaryTools);

    if (httpApiTools != null) builder.defaultTools(httpApiTools);
    builder.defaultTools(
        (Object[])
            dev.mikoto2000.rei.core.contextbudget.DeliveryToolRegistration.provider(tools)
                .getToolCallbacks());
    if (reviewTools != null)
      builder.defaultTools(
          (Object[])
              dev.mikoto2000.rei.core.contextbudget.DeliveryToolRegistration.provider(reviewTools)
                  .getToolCallbacks());
    if (rawResultTools != null)
      builder.defaultTools(
          (Object[])
              dev.mikoto2000.rei.core.contextbudget.DeliveryToolRegistration.provider(
                      rawResultTools)
                  .getToolCallbacks());
    if (processDependencyTools != null) builder.defaultTools(processDependencyTools);
    if (fileDependencyTools != null) builder.defaultTools(fileDependencyTools);
    if (dependencyTools != null) builder.defaultTools(dependencyTools);
    if (resumeContext != null && resumeContext.getIfAvailable() != null)
      builder.defaultAdvisors(
          new dev.mikoto2000.rei.core.chat.RunScopedAdvisor(resumeContext.getObject()));
    if (checkpointTools != null && checkpointTools.getIfAvailable() != null)
      builder.defaultTools(
          new ToolEventCallbackProvider(
              org.springframework.ai.tool.method.MethodToolCallbackProvider.builder()
                  .toolObjects(checkpointTools.getObject())
                  .build(),
              eventFactory,
              eventPublisher));
    if (workTools != null && workTools.getIfAvailable() != null)
      builder.defaultTools(
          new ToolEventCallbackProvider(
              org.springframework.ai.tool.method.MethodToolCallbackProvider.builder()
                  .toolObjects(workTools.getObject())
                  .build(),
              eventFactory,
              eventPublisher));
    if (episodeTools != null && episodeTools.getIfAvailable() != null)
      builder.defaultTools(episodeTools.getObject());
    if (paperTools != null && paperTools.getIfAvailable() != null)
      builder.defaultTools(paperTools.getObject());
    ToolEventCallbackProvider toolCallbackProvider = toolEventCallbackProvider.getIfAvailable();
    if (toolCallbackProvider != null) {
      builder.defaultTools(toolCallbackProvider);
    }

    // Lazy callback avoids a bean cycle through the model provider during ChatClient construction.
    if (externalAgentTools != null) builder.defaultTools((Object[]) externalAgentTools.callbacks());
    if (dotTools != null && dotTools.getIfAvailable() != null)
      builder.defaultTools(java.util.Arrays.asList(dotTools.getObject().callbacks()));
    if (subAgentTools != null)
      for (String name :
          dev.mikoto2000.rei.subagent.SubAgentParentToolCatalog.names(subAgentProperties))
        builder.defaultTools(
            new dev.mikoto2000.rei.subagent.LazyDelegationCallback(subAgentTools, name));
    if (computerUseTools != null) {
      var computer = computerUseTools.getIfAvailable();
      if (computer != null) {
        builder.defaultTools(computer.callback());
        builder.defaultSystem(
            systemPromptService.systemPrompt() + "\n\n" + computer.orchestrationPrompt());
      }
    }
    return builder.build();
  }
}
