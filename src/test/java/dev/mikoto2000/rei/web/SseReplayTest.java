package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.application.run.*;
import dev.mikoto2000.rei.event.*;
import java.time.Clock;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SseReplayTest {
  @Test void initialConnectionReplaysMoreThanClientQueueCapacityAndThenCompletes() throws Exception {
    var registry = new RunRegistry(Clock.systemUTC());
    registry.register(RunRegistryTest.context("run"));
    registry.transition("run", RunStatus.RUNNING, null);
    var bus = new InMemoryAgentEventBus();
    var factory = new AgentEventFactory(Clock.systemUTC());
    for (int i = 0; i < 1500; i++) bus.publish(factory.runStarted("run", "test", null));
    bus.publish(factory.runCompleted("run", 1));
    registry.transition("run", RunStatus.COMPLETED, null);
    try (var bridge = new SseBridge(bus, new RunService(registry), "")) {
      var sink = new SseBridgeTest.Sink();
      bridge.connect("run", null, sink);
      assertThat(sink.ended.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(sink.error).isNull();
      assertThat(sink.events).hasSize(1501);
      assertThat(sink.events.getLast().type()).isEqualTo("agent.run.completed");
      for (long cursor : new long[] {1501, 9000}) {
        var resumed = new SseBridgeTest.Sink();
        bridge.connect("run", cursor, resumed);
        assertThat(resumed.ended.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(resumed.events).isEmpty();
      }
    }
  }
  @Test void terminalStateWithoutStoredEventKeepsSubscriptionUntilEventArrives() throws Exception {
    var registry = new RunRegistry(Clock.systemUTC());
    registry.register(RunRegistryTest.context("run"));
    registry.transition("run", RunStatus.CANCELLED, null);
    var bus = new InMemoryAgentEventBus();
    try (var bridge = new SseBridge(bus, new RunService(registry), "")) {
      var sink = new SseBridgeTest.Sink();
      var connection = bridge.connect("run", null, sink);
      assertThat(connection.isClosed()).isFalse();
      bus.publish(new AgentEventFactory(Clock.systemUTC()).runCancelled("run", null));
      assertThat(sink.ended.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(sink.events).extracting(WebApiEventDto::type).containsExactly("agent.run.cancelled");
    }
  }
  @Test void expiredRunAndReplayDisappearTogether() {
    var clock = new RunRegistryTest.MutableClock();
    var registry = new RunRegistry(clock);
    registry.register(RunRegistryTest.context("run"));
    var bus = InMemoryAgentEventBus.withReplayBuffer(new ReplayBuffer(10000, clock));
    var service = new RunService(registry, bus, new AgentEventFactory(clock),
        new dev.mikoto2000.rei.core.service.CommandCancellationService(), id -> true);
    service.cancel("run");
    clock.now = clock.now.plusSeconds(1800);
    assertThatThrownBy(() -> service.get("run")).isInstanceOf(RunNotFoundException.class);
    var replay = bus.subscribe("run", 0, event -> {});
    assertThat(replay.replay()).isEmpty();
    replay.subscription().unsubscribe();
  }
}
