package dev.mikoto2000.rei.topic;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventPublisher;

public class DefaultTopicOrchestrator implements TopicOrchestrator {
  private static final Logger log = LoggerFactory.getLogger(DefaultTopicOrchestrator.class);

  private final TopicGeneratorService topicGeneratorService;
  private final TopicCandidateStore candidateStore;
  private final TopicGenerationContextProvider contextProvider;
  private final AgentActivityTracker activityTracker;
  private final AgentMessagePublisher messagePublisher;
  private final TopicGeneratorProperties properties;
  private final AgentEventFactory eventFactory;
  private final AgentEventPublisher eventPublisher;
  private final Clock clock;
  private final AtomicBoolean running = new AtomicBoolean(false);

  public DefaultTopicOrchestrator(TopicGeneratorService topicGeneratorService, TopicCandidateStore candidateStore,
      TopicGenerationContextProvider contextProvider, AgentActivityTracker activityTracker,
      AgentMessagePublisher messagePublisher, TopicGeneratorProperties properties,
      AgentEventFactory eventFactory, AgentEventPublisher eventPublisher, Clock clock) {
    this.topicGeneratorService = topicGeneratorService;
    this.candidateStore = candidateStore;
    this.contextProvider = contextProvider;
    this.activityTracker = activityTracker;
    this.messagePublisher = messagePublisher;
    this.properties = properties;
    this.eventFactory = eventFactory;
    this.eventPublisher = eventPublisher;
    this.clock = clock;
  }

  @Override
  public void onChatCompleted() {
    if (!properties.isEnabled()) return;
    List<TopicCandidate> candidates = topicGeneratorService.prepareCandidates(contextProvider.currentContext());
    candidateStore.replace(candidates, Instant.now(clock));
    eventPublisher.publish(eventFactory.topicCandidatesRefreshed(candidates.size()));
  }

  @Override
  public TopicGeneratorService.TopicRunResult onUserIdle() {
    if (!running.compareAndSet(false, true)) return TopicGeneratorService.TopicRunResult.skipped("running");
    long activityVersion = activityTracker.activityVersion();
    try {
      Instant now = Instant.now(clock);
      List<TopicCandidate> candidates = candidateStore.currentCandidates(now, properties.getCandidateMaxAge());
      TopicExecutionContext executionContext = new TopicExecutionContext("topic-" + UUID.randomUUID(), now,
          activityTracker.lastUserActivityAt(), activityTracker.lastAgentActivityAt(), activityTracker.isAgentBusy(),
          TopicTrigger.USER_IDLE);
      TopicGeneratorService.TopicRunResult result = topicGeneratorService.runWithCandidates(candidates, executionContext);
      if (!result.spoken() || result.message() == null || result.message().isBlank()) return result;
      if (activityTracker.isAgentBusy() || activityTracker.activityVersion() != activityVersion) {
        eventPublisher.publish(eventFactory.topicAutoSpeakSuppressed("stale activity"));
        return TopicGeneratorService.TopicRunResult.skipped("stale activity");
      }
      Instant publishedAt = Instant.now(clock);
      AgentMessage message = new AgentMessage(result.messageId(), "assistant", result.message(),
          MessageOrigin.TOPIC_GENERATOR, publishedAt);
      messagePublisher.publish(message);
      topicGeneratorService.completeSpoken(result, publishedAt);
      return result;
    } catch (RuntimeException exception) {
      log.warn("Topic orchestrator failed", exception);
      return TopicGeneratorService.TopicRunResult.skipped("orchestrator failed");
    } finally {
      running.set(false);
    }
  }
}
