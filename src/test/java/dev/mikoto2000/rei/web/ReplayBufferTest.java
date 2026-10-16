package dev.mikoto2000.rei.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import dev.mikoto2000.rei.event.ReplayBuffer;
import dev.mikoto2000.rei.event.ReplayGapException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReplayBufferTest {
  @Test
  void perRunEvictionDoesNotConfuseGlobalSequenceGapsWithMissingHistory() {
    var bus = InMemoryAgentEventBus.withReplayBuffer(new ReplayBuffer(2, Clock.systemUTC()));
    var factory = new AgentEventFactory(Clock.systemUTC());
    bus.publish(factory.runStarted("run", "first", null)); // 1
    bus.publish(factory.runStarted("other", "other", null)); // 2
    bus.publish(factory.runStarted("run", "second", null)); // 3
    var first = bus.subscribe("run", 0, event -> {});
    assertThat(first.replay()).extracting(AgentEvent::sequence).containsExactly(1L, 3L);
    first.subscription().unsubscribe();
    bus.publish(factory.runCompleted("run", 1)); // 4, evicts 1
    assertThatThrownBy(() -> bus.subscribe("run", 0, event -> {}))
        .isInstanceOf(ReplayGapException.class);
    var retained = bus.subscribe("run", 1, event -> {});
    assertThat(retained.replay()).extracting(AgentEvent::sequence).containsExactly(3L, 4L);
    assertThat(retained.terminalSequence()).isEqualTo(4L);
    retained.subscription().unsubscribe();
  }

  @Test
  void replayBoundaryAndLiveSubscriptionHaveNoDuplicatesOrMissingEvents() {
    var bus = new InMemoryAgentEventBus();
    var factory = new AgentEventFactory(Clock.systemUTC());
    bus.publish(factory.runStarted("run", "first", null));
    List<AgentEvent> live = new ArrayList<>();
    var subscription = bus.subscribe("run", 0, live::add);
    bus.publish(factory.runCompleted("run", 1));
    assertThat(subscription.replay()).extracting(AgentEvent::sequence).containsExactly(1L);
    assertThat(live).extracting(AgentEvent::sequence).containsExactly(2L);
    subscription.subscription().unsubscribe();
    var future = bus.subscribe("run", 100, live::add);
    assertThat(future.replay()).isEmpty();
    bus.publish(factory.runStarted("run", "later", null));
    assertThat(live).extracting(AgentEvent::sequence).containsExactly(2L, 3L);
    future.subscription().unsubscribe();
  }
}
