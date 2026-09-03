package dev.mikoto2000.rei.topic;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Component;

@Component
public class DefaultAgentActivityTracker implements AgentActivityTracker {
  private final Instant applicationStartedAt;
  private final AtomicReference<Instant> lastUserActivityAt;
  private final AtomicReference<Instant> lastAgentActivityAt;
  private final AtomicBoolean agentBusy = new AtomicBoolean(false);
  private final AtomicLong activityVersion = new AtomicLong();

  public DefaultAgentActivityTracker(Clock clock) {
    Instant now = Instant.now(clock);
    applicationStartedAt = now;
    lastUserActivityAt = new AtomicReference<>(now);
    lastAgentActivityAt = new AtomicReference<>(now);
  }

  @Override
  public Instant applicationStartedAt() {
    return applicationStartedAt;
  }

  @Override
  public Instant lastUserActivityAt() {
    return lastUserActivityAt.get();
  }

  @Override
  public Instant lastAgentActivityAt() {
    return lastAgentActivityAt.get();
  }

  @Override
  public boolean isAgentBusy() {
    return agentBusy.get();
  }

  @Override
  public long activityVersion() {
    return activityVersion.get();
  }

  @Override
  public void recordUserActivity(Instant at) {
    lastUserActivityAt.set(at);
    activityVersion.incrementAndGet();
  }

  @Override
  public void recordAgentStarted(Instant at) {
    agentBusy.set(true);
    lastAgentActivityAt.set(at);
    activityVersion.incrementAndGet();
  }

  @Override
  public void recordAgentCompleted(Instant at) {
    agentBusy.set(false);
    lastAgentActivityAt.set(at);
    activityVersion.incrementAndGet();
  }
}
