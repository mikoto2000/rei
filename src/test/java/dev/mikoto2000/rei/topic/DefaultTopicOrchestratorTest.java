package dev.mikoto2000.rei.topic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.mikoto2000.rei.conversation.ConversationLogStore;
import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import dev.mikoto2000.rei.llm.ConversationIds;

class DefaultTopicOrchestratorTest {

  @Test
  void chatCompletedRefreshesCandidateStoreWithoutPublishingMessage() {
    TopicGeneratorProperties properties = enabledProperties();
    InMemoryTopicCandidateStore store = new InMemoryTopicCandidateStore();
    RecordingMessagePublisher publisher = new RecordingMessagePublisher();
    DefaultTopicOrchestrator orchestrator = new DefaultTopicOrchestrator(
        service(properties, new TemplateTopicMessageGenerator()),
        store,
        () -> context(),
        new RecordingActivityTracker(now().minus(Duration.ofMinutes(5))),
        publisher,
        properties,
        new AgentEventFactory(clock()),
        new InMemoryAgentEventBus(),
        clock());

    orchestrator.onChatCompleted();

    assertEquals(List.of(candidate()), store.currentCandidates(now(), Duration.ofMinutes(30)));
    assertTrue(publisher.messages.isEmpty());
  }

  @Test
  void userIdlePublishesAssistantMessageBeforeTopicSpokenEvent() {
    TopicGeneratorProperties properties = enabledProperties();
    InMemoryTopicCandidateStore store = new InMemoryTopicCandidateStore();
    store.replace(List.of(candidate()), now().minus(Duration.ofMinutes(1)));
    InMemoryAgentEventBus bus = new InMemoryAgentEventBus();
    List<AgentEvent> events = new ArrayList<>();
    bus.subscribe(events::add);
    ConversationLogStore logStore = mock(ConversationLogStore.class);
    RecordingActivityTracker tracker = new RecordingActivityTracker(now().minus(Duration.ofMinutes(5)));
    EventAgentMessagePublisher publisher = new EventAgentMessagePublisher(logStore, new AgentEventFactory(clock()),
        bus, tracker);
    DefaultTopicOrchestrator orchestrator = new DefaultTopicOrchestrator(
        service(properties, new TemplateTopicMessageGenerator(), bus),
        store, () -> context(), tracker, publisher, properties, new AgentEventFactory(clock()), bus, clock());

    TopicGeneratorService.TopicRunResult result = orchestrator.onUserIdle();

    assertTrue(result.spoken());
    verify(logStore).append(ConversationIds.chat(), "assistant", result.message());
    List<AgentEventType> types = events.stream().map(AgentEvent::type).toList();
    assertTrue(types.indexOf(AgentEventType.MESSAGE_STARTED) < types.indexOf(AgentEventType.TOPIC_SPOKEN));
    assertEquals(AgentEventType.TOPIC_GENERATION_COMPLETED, types.getLast());
  }

  @Test
  void userActivityDuringGenerationPreventsPublishing() {
    TopicGeneratorProperties properties = enabledProperties();
    InMemoryTopicCandidateStore store = new InMemoryTopicCandidateStore();
    store.replace(List.of(candidate()), now().minus(Duration.ofMinutes(1)));
    InMemoryAgentEventBus bus = new InMemoryAgentEventBus();
    List<AgentEvent> events = new ArrayList<>();
    bus.subscribe(events::add);
    RecordingActivityTracker tracker = new RecordingActivityTracker(now().minus(Duration.ofMinutes(5)));
    RecordingMessagePublisher publisher = new RecordingMessagePublisher();
    TopicMessageGenerator mutatingGenerator = (candidate, context) -> {
      tracker.recordUserActivity(now().plusSeconds(1));
      return "stale message";
    };
    DefaultTopicOrchestrator orchestrator = new DefaultTopicOrchestrator(
        service(properties, mutatingGenerator, bus), store, () -> context(), tracker, publisher, properties,
        new AgentEventFactory(clock()), bus, clock());

    TopicGeneratorService.TopicRunResult result = orchestrator.onUserIdle();

    assertEquals("stale activity", result.reason());
    assertTrue(publisher.messages.isEmpty());
    assertFalse(events.stream().anyMatch(event -> event.type() == AgentEventType.TOPIC_SPOKEN));
    assertTrue(events.stream().anyMatch(event -> event.type() == AgentEventType.TOPIC_AUTO_SPEAK_SUPPRESSED));
  }

  private TopicGeneratorService service(TopicGeneratorProperties properties, TopicMessageGenerator messageGenerator) {
    return service(properties, messageGenerator, new InMemoryAgentEventBus());
  }

  private TopicGeneratorService service(TopicGeneratorProperties properties, TopicMessageGenerator messageGenerator,
      InMemoryAgentEventBus bus) {
    return new TopicGeneratorService(
        List.of(context -> List.of(candidate())),
        new DeterministicTopicRanker(),
        new DefaultSpeakDecisionPolicy(properties),
        messageGenerator,
        new InMemoryCuriosityQueue(),
        properties,
        new AgentEventFactory(clock()),
        bus,
        clock());
  }

  private TopicGeneratorProperties enabledProperties() {
    TopicGeneratorProperties properties = new TopicGeneratorProperties();
    properties.setEnabled(true);
    properties.setMinimumTopicSpeakInterval(Duration.ZERO);
    properties.setCandidateMaxAge(Duration.ofMinutes(30));
    return properties;
  }

  private TopicGenerationContext context() {
    return new TopicGenerationContext(List.of(), List.of(), now(), List.of());
  }

  private TopicCandidate candidate() {
    return new TopicCandidate("id", "topic", "reason", TopicType.FOLLOW_UP, TopicSource.CONVERSATION,
        1, 1, 1, 0, 1, now());
  }

  private Instant now() {
    return Instant.parse("2026-09-02T00:10:00Z");
  }

  private Clock clock() {
    return Clock.fixed(now(), ZoneOffset.UTC);
  }

  private static class RecordingMessagePublisher implements AgentMessagePublisher {
    private final List<AgentMessage> messages = new ArrayList<>();
    @Override public void publish(AgentMessage message) { messages.add(message); }
  }

  private static class RecordingActivityTracker implements AgentActivityTracker {
    private final Instant applicationStartedAt;
    private Instant lastUserActivityAt;
    private Instant lastAgentActivityAt;
    private boolean agentBusy;
    private long activityVersion;

    RecordingActivityTracker(Instant applicationStartedAt) {
      this.applicationStartedAt = applicationStartedAt;
      this.lastUserActivityAt = applicationStartedAt;
      this.lastAgentActivityAt = applicationStartedAt;
    }

    @Override public Instant applicationStartedAt() { return applicationStartedAt; }
    @Override public Instant lastUserActivityAt() { return lastUserActivityAt; }
    @Override public Instant lastAgentActivityAt() { return lastAgentActivityAt; }
    @Override public boolean isAgentBusy() { return agentBusy; }
    @Override public long activityVersion() { return activityVersion; }
    @Override public void recordUserActivity(Instant at) { lastUserActivityAt = at; activityVersion++; }
    @Override public void recordAgentStarted(Instant at) { lastAgentActivityAt = at; agentBusy = true; activityVersion++; }
    @Override public void recordAgentCompleted(Instant at) { lastAgentActivityAt = at; agentBusy = false; activityVersion++; }
  }
}
