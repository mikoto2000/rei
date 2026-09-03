package dev.mikoto2000.rei.topic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import dev.mikoto2000.rei.event.TopicCandidateScoredPayload;
import dev.mikoto2000.rei.event.TopicGenerationFailedPayload;
import dev.mikoto2000.rei.event.TopicSpeakSkippedPayload;

class TopicGeneratorServiceEventTest {

  @Test
  void publishesFullSpokenLifecycleEvents() {
    TopicGeneratorProperties properties = enabledProperties();
    InMemoryAgentEventBus bus = new InMemoryAgentEventBus();
    List<AgentEvent> events = new ArrayList<>();
    bus.subscribe(events::add);
    TopicGeneratorService service = new TopicGeneratorService(
        List.of(context -> List.of(high("secret=abc"))),
        new DeterministicTopicRanker(),
        new DefaultSpeakDecisionPolicy(properties),
        new TemplateTopicMessageGenerator(),
        new InMemoryCuriosityQueue(),
        properties,
        new AgentEventFactory(clock()),
        bus,
        clock());

    TopicGeneratorService.TopicRunResult result = service.run("run", context(), false, false);

    assertTrue(result.spoken());
    assertEquals(AgentEventType.TOPIC_GENERATION_STARTED, events.getFirst().type());
    assertTrue(events.stream().anyMatch(event -> event.type() == AgentEventType.TOPIC_CANDIDATE_GENERATED));
    AgentEvent scored = events.stream()
        .filter(event -> event.type() == AgentEventType.TOPIC_CANDIDATE_SCORED)
        .findFirst()
        .orElseThrow();
    assertEquals(new DeterministicTopicRanker().rank(List.of(high("secret=abc")), new TopicRankingContext(List.of()))
        .getFirst().score(), ((TopicCandidateScoredPayload) scored.payload()).score());
    assertTrue(events.stream().anyMatch(event -> event.type() == AgentEventType.TOPIC_SELECTED));
    assertTrue(events.stream().anyMatch(event -> event.type() == AgentEventType.TOPIC_SPOKEN));
    assertEquals(AgentEventType.TOPIC_GENERATION_COMPLETED, events.getLast().type());
    assertEquals(List.of(
        AgentEventType.TOPIC_GENERATION_STARTED,
        AgentEventType.TOPIC_CANDIDATE_GENERATED,
        AgentEventType.TOPIC_CANDIDATE_SCORED,
        AgentEventType.TOPIC_SELECTED,
        AgentEventType.TOPIC_SPOKEN,
        AgentEventType.TOPIC_GENERATION_COMPLETED), events.stream().map(AgentEvent::type).toList());
    assertFalse(events.toString().contains("secret=abc"));
  }

  @Test
  void lowScoreCandidateIsRejectedAndNoCandidateIsSkipped() {
    TopicGeneratorProperties properties = enabledProperties();
    InMemoryAgentEventBus bus = new InMemoryAgentEventBus();
    List<AgentEvent> events = new ArrayList<>();
    bus.subscribe(events::add);
    TopicGeneratorService service = new TopicGeneratorService(
        List.of(context -> List.of(low())),
        new DeterministicTopicRanker(),
        new DefaultSpeakDecisionPolicy(properties),
        new TemplateTopicMessageGenerator(),
        new InMemoryCuriosityQueue(),
        properties,
        new AgentEventFactory(clock()),
        bus,
        clock());

    TopicGeneratorService.TopicRunResult result = service.run("run", context(), false, false);

    assertFalse(result.spoken());
    assertTrue(events.stream().anyMatch(event -> event.type() == AgentEventType.TOPIC_CANDIDATE_REJECTED));
    TopicSpeakSkippedPayload skipped = (TopicSpeakSkippedPayload) events.stream()
        .filter(event -> event.type() == AgentEventType.TOPIC_SPEAK_SKIPPED)
        .findFirst()
        .orElseThrow()
        .payload();
    assertEquals(TopicSpeakSkipReason.NO_CANDIDATE, skipped.reason());
    assertEquals(AgentEventType.TOPIC_GENERATION_COMPLETED, events.getLast().type());
  }

  @Test
  void cooldownSkipsSelectedCandidateWithoutRejectingIt() {
    TopicGeneratorProperties properties = enabledProperties();
    properties.setMinimumScore(0.4);
    InMemoryAgentEventBus bus = new InMemoryAgentEventBus();
    List<AgentEvent> events = new ArrayList<>();
    bus.subscribe(events::add);
    TopicGeneratorService service = new TopicGeneratorService(
        List.of(context -> List.of(high("first"), high("second"))),
        new DeterministicTopicRanker(),
        new DefaultSpeakDecisionPolicy(properties),
        new TemplateTopicMessageGenerator(),
        new InMemoryCuriosityQueue(),
        properties,
        new AgentEventFactory(clock()),
        bus,
        clock());

    assertTrue(service.run("run-1", context(), false, false).spoken());
    events.clear();
    TopicGeneratorService.TopicRunResult result = service.run("run-2", context(), false, false);

    assertFalse(result.spoken());
    assertTrue(events.stream().anyMatch(event -> event.type() == AgentEventType.TOPIC_SELECTED));
    assertTrue(events.stream().noneMatch(event -> event.type() == AgentEventType.TOPIC_CANDIDATE_REJECTED));
    TopicSpeakSkippedPayload skipped = (TopicSpeakSkippedPayload) events.stream()
        .filter(event -> event.type() == AgentEventType.TOPIC_SPEAK_SKIPPED)
        .findFirst()
        .orElseThrow()
        .payload();
    assertEquals(TopicSpeakSkipReason.COOLDOWN, skipped.reason());
    assertEquals(Instant.parse("2026-09-02T00:30:00Z"), skipped.nextSpeakAllowedAt());
  }

  @Test
  void candidateGenerationFailurePublishesFailedEvent() {
    TopicGeneratorProperties properties = enabledProperties();
    InMemoryAgentEventBus bus = new InMemoryAgentEventBus();
    List<AgentEvent> events = new ArrayList<>();
    bus.subscribe(events::add);
    TopicGeneratorService service = service(properties, List.of(context -> {
      throw new IllegalStateException("credential=abc");
    }), new DeterministicTopicRanker(), new TemplateTopicMessageGenerator(), bus);

    TopicGeneratorService.TopicRunResult result = service.run("run", context(), false, false);

    assertFalse(result.spoken());
    assertEquals(AgentEventType.TOPIC_GENERATION_STARTED, events.getFirst().type());
    assertEquals(AgentEventType.TOPIC_GENERATION_FAILED, events.getLast().type());
    TopicGenerationFailedPayload failed = assertInstanceOf(TopicGenerationFailedPayload.class, events.getLast().payload());
    assertEquals(TopicGenerationStage.CANDIDATE_GENERATION, failed.stage());
    assertFalse(events.toString().contains("credential=abc"));
  }

  @Test
  void rankingFailurePublishesFailedEvent() {
    TopicGeneratorProperties properties = enabledProperties();
    InMemoryAgentEventBus bus = new InMemoryAgentEventBus();
    List<AgentEvent> events = new ArrayList<>();
    bus.subscribe(events::add);
    TopicGeneratorService service = service(properties, List.of(context -> List.of(high("reason"))),
        (candidates, context) -> { throw new IllegalStateException("ranking failed"); },
        new TemplateTopicMessageGenerator(), bus);

    assertFalse(service.run("run", context(), false, false).spoken());

    TopicGenerationFailedPayload failed = assertInstanceOf(TopicGenerationFailedPayload.class, events.getLast().payload());
    assertEquals(TopicGenerationStage.RANKING, failed.stage());
  }

  @Test
  void messageGenerationFailurePublishesFailedEvent() {
    TopicGeneratorProperties properties = enabledProperties();
    InMemoryAgentEventBus bus = new InMemoryAgentEventBus();
    List<AgentEvent> events = new ArrayList<>();
    bus.subscribe(events::add);
    TopicGeneratorService service = service(properties, List.of(context -> List.of(high("reason"))),
        new DeterministicTopicRanker(),
        (candidate, context) -> { throw new IllegalStateException("message failed"); },
        bus);

    assertFalse(service.run("run", context(), false, false).spoken());

    TopicGenerationFailedPayload failed = assertInstanceOf(TopicGenerationFailedPayload.class, events.getLast().payload());
    assertEquals(TopicGenerationStage.MESSAGE_GENERATION, failed.stage());
  }

  @Test
  void disabledFeatureFlagDoesNotCallGenerators() {
    @SuppressWarnings("unchecked")
    TopicCandidateGenerator generator = org.mockito.Mockito.mock(TopicCandidateGenerator.class);
    TopicGeneratorService service = new TopicGeneratorService(
        List.of(generator),
        new DeterministicTopicRanker(),
        new DefaultSpeakDecisionPolicy(new TopicGeneratorProperties()),
        new TemplateTopicMessageGenerator(),
        new InMemoryCuriosityQueue(),
        new TopicGeneratorProperties(),
        new AgentEventFactory(clock()),
        new InMemoryAgentEventBus(),
        clock());

    assertEquals("disabled", service.run("run", context(), false, false).reason());
    verifyNoInteractions(generator);
  }

  private TopicGeneratorService service(TopicGeneratorProperties properties, List<TopicCandidateGenerator> generators,
      TopicRanker ranker, TopicMessageGenerator messageGenerator, InMemoryAgentEventBus bus) {
    return new TopicGeneratorService(
        generators,
        ranker,
        new DefaultSpeakDecisionPolicy(properties),
        messageGenerator,
        new InMemoryCuriosityQueue(),
        properties,
        new AgentEventFactory(clock()),
        bus,
        clock());
  }

  private TopicCandidate high(String reason) {
    return new TopicCandidate("id", "topic", reason, TopicType.FOLLOW_UP, TopicSource.CONVERSATION,
        1, 1, 1, 0, 1, Instant.parse("2026-09-02T00:00:00Z"));
  }

  private TopicCandidate low() {
    return new TopicCandidate("id", "topic", "", TopicType.FOLLOW_UP, TopicSource.CONVERSATION,
        0, 0, 0, 1, 1, Instant.parse("2026-09-02T00:00:00Z"));
  }

  private TopicGeneratorProperties enabledProperties() {
    TopicGeneratorProperties properties = new TopicGeneratorProperties();
    properties.setEnabled(true);
    return properties;
  }

  private TopicGenerationContext context() {
    return new TopicGenerationContext(List.of(), List.of(), Instant.parse("2026-09-02T00:00:00Z"), List.of());
  }

  private Clock clock() {
    return Clock.fixed(Instant.parse("2026-09-02T00:00:00Z"), ZoneOffset.UTC);
  }
}
