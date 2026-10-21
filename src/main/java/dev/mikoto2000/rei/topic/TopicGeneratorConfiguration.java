package dev.mikoto2000.rei.topic;

import dev.mikoto2000.rei.core.service.ModelHolderService;
import java.time.Clock;
import java.util.List;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/** 話題生成の構成要素を接続し、設定に従って利用できるようにする。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TopicGeneratorProperties.class)
public class TopicGeneratorConfiguration {

  /** 話題の管理で用いる話題候補入力の解釈を返す。 */
  @Bean
  TopicCandidateParser topicCandidateParser(Clock clock) {
    return new TopicCandidateParser(new JsonMapper(), clock);
  }

  /** 話題の管理で用いる言語モデル話題候補生成を返す。 */
  @Bean
  LlmTopicCandidateGenerator llmTopicCandidateGenerator(
      ChatModel chatModel,
      ModelHolderService modelHolderService,
      TopicCandidateParser parser,
      TopicGeneratorProperties properties) {
    return new LlmTopicCandidateGenerator(chatModel, modelHolderService, parser, properties);
  }

  /** 話題の管理で用いる情報への関心話題候補生成を返す。 */
  @Bean
  CuriosityTopicCandidateGenerator curiosityTopicCandidateGenerator(
      CuriosityQueue queue, TopicGeneratorProperties properties) {
    return new CuriosityTopicCandidateGenerator(queue, properties);
  }

  /** 話題の管理で用いる利用先の検出生成の初期値生成を返す。 */
  @Bean
  DiscoverySeedGenerator discoverySeedGenerator(TopicGeneratorProperties properties) {
    return new DiscoverySeedGenerator(properties);
  }

  /** 話題の管理で用いる利用先の検出話題候補生成を返す。 */
  @Bean
  DiscoveryTopicCandidateGenerator discoveryTopicCandidateGenerator(
      DiscoverySeedGenerator seedGenerator,
      ObjectProvider<DiscoverySource> sources,
      DiscoverySeenRepository seenRepository,
      TopicGeneratorProperties properties) {
    return new DiscoveryTopicCandidateGenerator(
        seedGenerator, sources.stream().toList(), seenRepository, properties);
  }

  /** 話題の管理で用いる話題Rankerを返す。 */
  @Bean
  TopicRanker topicRanker() {
    return new DeterministicTopicRanker();
  }

  /** 話題の管理で用いる読み上げ判断結果判断基準を返す。 */
  @Bean
  SpeakDecisionPolicy speakDecisionPolicy(TopicGeneratorProperties properties) {
    return new DefaultSpeakDecisionPolicy(properties);
  }

  /** 話題の管理で用いる話題メッセージ生成を返す。 */
  @Bean
  TopicMessageGenerator topicMessageGenerator() {
    return new TemplateTopicMessageGenerator();
  }

  /** 話題の管理で用いる話題生成サービスを返す。 */
  @Bean
  TopicGeneratorService topicGeneratorService(
      ObjectProvider<TopicCandidateGenerator> generators,
      TopicRanker ranker,
      SpeakDecisionPolicy speakDecisionPolicy,
      TopicMessageGenerator messageGenerator,
      CuriosityQueue curiosityQueue,
      TopicGeneratorProperties properties,
      dev.mikoto2000.rei.event.AgentEventFactory eventFactory,
      dev.mikoto2000.rei.event.AgentEventPublisher eventPublisher,
      Clock clock) {
    List<TopicCandidateGenerator> generatorList = generators.stream().toList();
    return new TopicGeneratorService(
        generatorList,
        ranker,
        speakDecisionPolicy,
        messageGenerator,
        curiosityQueue,
        properties,
        eventFactory,
        eventPublisher,
        clock);
  }

  /** 話題の管理で用いる話題候補保存先を返す。 */
  @Bean
  TopicCandidateStore topicCandidateStore() {
    return new InMemoryTopicCandidateStore();
  }

  /** 話題の管理で用いる操作がない状態話題実行条件を返す。 */
  @Bean
  IdleTopicTrigger idleTopicTrigger(
      TopicGeneratorProperties properties, AgentActivityTracker activityTracker) {
    return new DefaultIdleTopicTrigger(properties, activityTracker);
  }

  /** 話題の管理で用いる話題候補再確認実行先を返す。 */
  @Bean(destroyMethod = "shutdownNow")
  java.util.concurrent.ExecutorService topicCandidateRefreshExecutor() {
    // One active refresh and only the latest waiting request. Never run generation on the caller.
    return new java.util.concurrent.ThreadPoolExecutor(
        1,
        1,
        0,
        java.util.concurrent.TimeUnit.MILLISECONDS,
        new java.util.concurrent.ArrayBlockingQueue<>(1),
        task -> {
          var thread = new Thread(task, "rei-topic-refresh");
          thread.setDaemon(true);
          return thread;
        },
        new java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy());
  }

  /** 話題の管理で用いる話題Orchestratorを返す。 */
  @Bean
  TopicOrchestrator topicOrchestrator(
      TopicGeneratorService topicGeneratorService,
      TopicCandidateStore candidateStore,
      TopicGenerationContextProvider contextProvider,
      AgentActivityTracker activityTracker,
      AgentMessagePublisher messagePublisher,
      TopicGeneratorProperties properties,
      dev.mikoto2000.rei.event.AgentEventFactory eventFactory,
      dev.mikoto2000.rei.event.AgentEventPublisher eventPublisher,
      Clock clock,
      @org.springframework.beans.factory.annotation.Qualifier("topicCandidateRefreshExecutor")
          java.util.concurrent.ExecutorService refreshExecutor) {
    return new DefaultTopicOrchestrator(
        topicGeneratorService,
        candidateStore,
        contextProvider,
        activityTracker,
        messagePublisher,
        properties,
        eventFactory,
        eventPublisher,
        clock,
        refreshExecutor);
  }

  /** 話題の管理で用いる操作がない状態話題実行予定の管理を返す。 */
  @Bean
  IdleTopicScheduler idleTopicScheduler(
      IdleTopicTrigger idleTopicTrigger,
      TopicOrchestrator topicOrchestrator,
      Clock clock,
      dev.mikoto2000.rei.event.AgentEventFactory eventFactory,
      dev.mikoto2000.rei.event.AgentEventPublisher eventPublisher) {
    return new IdleTopicScheduler(
        idleTopicTrigger, topicOrchestrator, clock, eventFactory, eventPublisher);
  }
}
