package dev.mikoto2000.rei.topic;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;

class IdleTopicSchedulerTest {

  @Test
  void tickRunsOrchestratorWhenIdleTriggerAccepts() {
    IdleTopicTrigger trigger = mock(IdleTopicTrigger.class);
    TopicOrchestrator orchestrator = mock(TopicOrchestrator.class);
    Instant now = Instant.parse("2026-09-02T00:00:00Z");
    when(trigger.evaluate(now)).thenReturn(IdleTriggerDecision.accepted(Duration.ofMinutes(3), Duration.ofMinutes(2)));
    InMemoryAgentEventBus bus = new InMemoryAgentEventBus();
    IdleTopicScheduler scheduler = new IdleTopicScheduler(trigger, orchestrator, Clock.fixed(now, ZoneOffset.UTC),
        new AgentEventFactory(Clock.fixed(now, ZoneOffset.UTC)), bus);

    scheduler.tick();

    verify(orchestrator).onUserIdle();
  }

  @Test
  void tickSkipsOrchestratorWhenIdleTriggerRejects() {
    IdleTopicTrigger trigger = mock(IdleTopicTrigger.class);
    TopicOrchestrator orchestrator = mock(TopicOrchestrator.class);
    Instant now = Instant.parse("2026-09-02T00:00:00Z");
    when(trigger.evaluate(now)).thenReturn(IdleTriggerDecision.rejected(Duration.ZERO,
        IdleTriggerRejectReason.INSUFFICIENT_IDLE, Duration.ofMinutes(2)));
    InMemoryAgentEventBus bus = new InMemoryAgentEventBus();
    IdleTopicScheduler scheduler = new IdleTopicScheduler(trigger, orchestrator, Clock.fixed(now, ZoneOffset.UTC),
        new AgentEventFactory(Clock.fixed(now, ZoneOffset.UTC)), bus);

    scheduler.tick();

    verify(orchestrator, never()).onUserIdle();
  }

  @Test
  void tickPublishesRejectedDecisionOnlyWhenReasonChanges() {
    IdleTopicTrigger trigger = mock(IdleTopicTrigger.class);
    TopicOrchestrator orchestrator = mock(TopicOrchestrator.class);
    Instant now = Instant.parse("2026-09-02T00:00:00Z");
    when(trigger.evaluate(now)).thenReturn(
        IdleTriggerDecision.rejected(Duration.ofSeconds(30), IdleTriggerRejectReason.INSUFFICIENT_IDLE,
            Duration.ofMinutes(2)),
        IdleTriggerDecision.rejected(Duration.ofSeconds(60), IdleTriggerRejectReason.INSUFFICIENT_IDLE,
            Duration.ofMinutes(2)),
        IdleTriggerDecision.rejected(Duration.ofMinutes(2), IdleTriggerRejectReason.AGENT_BUSY,
            Duration.ofMinutes(2)));
    InMemoryAgentEventBus bus = new InMemoryAgentEventBus();
    java.util.List<AgentEvent> events = new java.util.ArrayList<>();
    bus.subscribe(events::add);
    IdleTopicScheduler scheduler = new IdleTopicScheduler(trigger, orchestrator, Clock.fixed(now, ZoneOffset.UTC),
        new AgentEventFactory(Clock.fixed(now, ZoneOffset.UTC)), bus);

    scheduler.tick();
    scheduler.tick();
    scheduler.tick();

    assertEventTypes(events, AgentEventType.TOPIC_IDLE_TRIGGER_EVALUATED,
        AgentEventType.TOPIC_IDLE_TRIGGER_EVALUATED);
  }

  private void assertEventTypes(java.util.List<AgentEvent> events, AgentEventType... types) {
    org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of(types), events.stream().map(AgentEvent::type).toList());
  }
}
