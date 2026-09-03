package dev.mikoto2000.rei.topic;

import java.time.Clock;
import java.util.List;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import dev.mikoto2000.rei.core.service.ModelHolderService;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TopicGeneratorProperties.class)
public class TopicGeneratorConfiguration {

  @Bean
  TopicCandidateParser topicCandidateParser(Clock clock) {
    return new TopicCandidateParser(new JsonMapper(), clock);
  }

  @Bean
  LlmTopicCandidateGenerator llmTopicCandidateGenerator(ChatModel chatModel, ModelHolderService modelHolderService,
      TopicCandidateParser parser, TopicGeneratorProperties properties) {
    return new LlmTopicCandidateGenerator(chatModel, modelHolderService, parser, properties);
  }

  @Bean
  CuriosityTopicCandidateGenerator curiosityTopicCandidateGenerator(CuriosityQueue queue,
      TopicGeneratorProperties properties) {
    return new CuriosityTopicCandidateGenerator(queue, properties);
  }

  @Bean
  DiscoverySeedGenerator discoverySeedGenerator(TopicGeneratorProperties properties) {
    return new DiscoverySeedGenerator(properties);
  }

  @Bean
  DiscoveryTopicCandidateGenerator discoveryTopicCandidateGenerator(DiscoverySeedGenerator seedGenerator,
      ObjectProvider<DiscoverySource> sources, DiscoverySeenRepository seenRepository,
      TopicGeneratorProperties properties) {
    return new DiscoveryTopicCandidateGenerator(seedGenerator, sources.stream().toList(), seenRepository, properties);
  }

  @Bean
  TopicRanker topicRanker() {
    return new DeterministicTopicRanker();
  }

  @Bean
  SpeakDecisionPolicy speakDecisionPolicy(TopicGeneratorProperties properties) {
    return new DefaultSpeakDecisionPolicy(properties);
  }

  @Bean
  TopicMessageGenerator topicMessageGenerator() {
    return new TemplateTopicMessageGenerator();
  }

  @Bean
  TopicGeneratorService topicGeneratorService(ObjectProvider<TopicCandidateGenerator> generators,
      TopicRanker ranker, SpeakDecisionPolicy speakDecisionPolicy, TopicMessageGenerator messageGenerator,
      CuriosityQueue curiosityQueue, TopicGeneratorProperties properties,
      dev.mikoto2000.rei.event.AgentEventFactory eventFactory,
      dev.mikoto2000.rei.event.AgentEventPublisher eventPublisher,
      Clock clock) {
    List<TopicCandidateGenerator> generatorList = generators.stream().toList();
    return new TopicGeneratorService(generatorList, ranker, speakDecisionPolicy, messageGenerator, curiosityQueue,
        properties, eventFactory, eventPublisher, clock);
  }

  @Bean
  TopicCandidateStore topicCandidateStore() {
    return new InMemoryTopicCandidateStore();
  }

  @Bean
  IdleTopicTrigger idleTopicTrigger(TopicGeneratorProperties properties, AgentActivityTracker activityTracker) {
    return new DefaultIdleTopicTrigger(properties, activityTracker);
  }

  @Bean
  TopicOrchestrator topicOrchestrator(TopicGeneratorService topicGeneratorService,
      TopicCandidateStore candidateStore, TopicGenerationContextProvider contextProvider,
      AgentActivityTracker activityTracker, AgentMessagePublisher messagePublisher,
      TopicGeneratorProperties properties, dev.mikoto2000.rei.event.AgentEventFactory eventFactory,
      dev.mikoto2000.rei.event.AgentEventPublisher eventPublisher, Clock clock) {
    return new DefaultTopicOrchestrator(topicGeneratorService, candidateStore, contextProvider, activityTracker,
        messagePublisher, properties, eventFactory, eventPublisher, clock);
  }

  @Bean
  IdleTopicScheduler idleTopicScheduler(IdleTopicTrigger idleTopicTrigger, TopicOrchestrator topicOrchestrator,
      Clock clock, dev.mikoto2000.rei.event.AgentEventFactory eventFactory,
      dev.mikoto2000.rei.event.AgentEventPublisher eventPublisher) {
    return new IdleTopicScheduler(idleTopicTrigger, topicOrchestrator, clock, eventFactory, eventPublisher);
  }
}
