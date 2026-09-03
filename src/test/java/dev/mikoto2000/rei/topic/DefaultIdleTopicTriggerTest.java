package dev.mikoto2000.rei.topic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class DefaultIdleTopicTriggerTest {

  @Test
  void acceptsWhenFeatureEnabledAgentFreeAndIdleLongEnough() {
    TopicGeneratorProperties properties = enabledProperties();
    TestActivityTracker tracker = new TestActivityTracker(now().minus(Duration.ofMinutes(10)));
    tracker.lastUserActivityAt = now().minus(Duration.ofMinutes(3));
    tracker.lastAgentActivityAt = now().minus(Duration.ofMinutes(4));

    IdleTriggerDecision decision = new DefaultIdleTopicTrigger(properties, tracker).evaluate(now());

    assertTrue(decision.accepted());
    assertEquals(Duration.ofMinutes(3), decision.idleDuration());
  }

  @Test
  void rejectsWhenRecentUserActivityIsInsideMinimumIdle() {
    TopicGeneratorProperties properties = enabledProperties();
    TestActivityTracker tracker = new TestActivityTracker(now().minus(Duration.ofMinutes(10)));
    tracker.lastUserActivityAt = now().minus(Duration.ofSeconds(30));
    tracker.lastAgentActivityAt = now().minus(Duration.ofMinutes(4));

    IdleTriggerDecision decision = new DefaultIdleTopicTrigger(properties, tracker).evaluate(now());

    assertFalse(decision.accepted());
    assertEquals(IdleTriggerRejectReason.INSUFFICIENT_IDLE, decision.rejectReason());
  }

  @Test
  void rejectsWhenAgentIsBusy() {
    TopicGeneratorProperties properties = enabledProperties();
    TestActivityTracker tracker = new TestActivityTracker(now().minus(Duration.ofMinutes(10)));
    tracker.lastUserActivityAt = now().minus(Duration.ofMinutes(3));
    tracker.lastAgentActivityAt = now().minus(Duration.ofMinutes(4));
    tracker.agentBusy = true;

    IdleTriggerDecision decision = new DefaultIdleTopicTrigger(properties, tracker).evaluate(now());

    assertFalse(decision.accepted());
    assertEquals(IdleTriggerRejectReason.AGENT_BUSY, decision.rejectReason());
  }

  @Test
  void rejectsWhenApplicationStartupIsStillRecent() {
    TopicGeneratorProperties properties = enabledProperties();
    TestActivityTracker tracker = new TestActivityTracker(now().minus(Duration.ofSeconds(20)));
    tracker.lastUserActivityAt = now().minus(Duration.ofMinutes(3));
    tracker.lastAgentActivityAt = now().minus(Duration.ofMinutes(3));

    IdleTriggerDecision decision = new DefaultIdleTopicTrigger(properties, tracker).evaluate(now());

    assertFalse(decision.accepted());
    assertEquals(IdleTriggerRejectReason.INSUFFICIENT_IDLE, decision.rejectReason());
  }

  private TopicGeneratorProperties enabledProperties() {
    TopicGeneratorProperties properties = new TopicGeneratorProperties();
    properties.setEnabled(true);
    properties.getIdleTrigger().setEnabled(true);
    properties.getIdleTrigger().setMinimumIdle(Duration.ofMinutes(2));
    return properties;
  }

  private Instant now() {
    return Instant.parse("2026-09-02T00:10:00Z");
  }

  private static class TestActivityTracker implements AgentActivityTracker {
    private final Instant applicationStartedAt;
    private Instant lastUserActivityAt;
    private Instant lastAgentActivityAt;
    private boolean agentBusy;

    TestActivityTracker(Instant applicationStartedAt) {
      this.applicationStartedAt = applicationStartedAt;
      this.lastUserActivityAt = applicationStartedAt;
      this.lastAgentActivityAt = applicationStartedAt;
    }

    @Override public Instant applicationStartedAt() { return applicationStartedAt; }
    @Override public Instant lastUserActivityAt() { return lastUserActivityAt; }
    @Override public Instant lastAgentActivityAt() { return lastAgentActivityAt; }
    @Override public boolean isAgentBusy() { return agentBusy; }
    @Override public long activityVersion() { return 0; }
    @Override public void recordUserActivity(Instant at) { lastUserActivityAt = at; }
    @Override public void recordAgentStarted(Instant at) { lastAgentActivityAt = at; agentBusy = true; }
    @Override public void recordAgentCompleted(Instant at) { lastAgentActivityAt = at; agentBusy = false; }
  }
}
