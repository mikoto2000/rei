package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.application.run.*;
import dev.mikoto2000.rei.event.*;
import java.time.Clock;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SseReplayTest {
  @Test void liveToolStartThenReconnectReplaysSemanticCompletionInGlobalOrder() throws Exception {
    var registry = new RunRegistry(Clock.systemUTC());
    var context = RunRegistryTest.context("run");
    registry.register(context);
    var bus = new InMemoryAgentEventBus();
    var f = new AgentEventFactory(Clock.systemUTC());
    var received = new java.util.concurrent.CountDownLatch(3);
    try (var bridge = new SseBridge(bus, new RunService(registry), "secret-value")) {
      var first = new SseBridgeTest.Sink() {
        public void event(WebApiEventDto event) { events.add(event); received.countDown(); }
      };
      var connection = bridge.connect("run", first);
      bus.publish(f.runStarted("run", "test", null));
      bus.publish(f.llmRequestStarted("run", "request", "chat").withOwnership(context));
      bus.publish(f.toolStarted("call", "read", "secret-value").withOwnership(context));
      assertThat(received.await(5, TimeUnit.SECONDS)).isTrue();
      connection.close();
      bus.publish(f.runStarted("another-run", "test", null));
      bus.publish(f.toolCompleted("call", "read", 18, "secret-value").withOwnership(context));
      bus.publish(f.messageDelta("message", "answer").withOwnership(context));
      var resumed = new SseBridgeTest.Sink();
      bridge.connect("run", first.events.getLast().sequence(), resumed);
      bus.publish(f.llmResponseCompleted("run", "request", 50).withOwnership(context));
      bus.publish(f.messageCompleted("message", "assistant", "answer").withOwnership(context));
      bus.publish(f.runCompleted("run", 55));
      assertThat(resumed.ended.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(resumed.events).extracting(WebApiEventDto::sequence).containsExactly(5L, 6L, 7L, 8L, 9L);
      assertThat(resumed.events).extracting(WebApiEventDto::type).containsExactly("tool.completed", "message.delta",
          "llm.response.completed", "message.completed", "agent.run.completed");
      assertThat(resumed.events.getFirst().payload()).containsEntry("toolCallId", "call").containsEntry("duration", 18L);
      assertThat(first.events.toString() + resumed.events).doesNotContain("secret-value");
    }
  }
  @Test void disconnectThenResumeDeliversOnlyUnacknowledgedEventsAndNormalizesCancelledReplay() throws Exception {
    var registry = new RunRegistry(Clock.systemUTC());
    registry.register(RunRegistryTest.context("run"));
    registry.transition("run", RunStatus.RUNNING, null);
    var bus = new InMemoryAgentEventBus();
    var events = new AgentEventFactory(Clock.systemUTC());
    var received = new java.util.concurrent.CountDownLatch(1);
    try (var bridge = new SseBridge(bus, new RunService(registry), "")) {
      var first = new SseBridgeTest.Sink() {
        public void event(WebApiEventDto event) { this.events.add(event); received.countDown(); }
      };
      var connection = bridge.connect("run", first);
      bus.publish(events.runStarted("run", "test", null));
      assertThat(received.await(5, TimeUnit.SECONDS)).isTrue();
      connection.close();
      registry.transition("run", RunStatus.CANCELLED, null);
      bus.publish(events.runFailed("run", new ErrorInformation("cancelled", "cancelled", null)));
      var resumed = new SseBridgeTest.Sink();
      bridge.connect("run", 1L, resumed);
      assertThat(resumed.ended.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(resumed.events).extracting(WebApiEventDto::sequence).containsExactly(2L);
      assertThat(resumed.events).extracting(WebApiEventDto::type).containsExactly("agent.run.cancelled");
    }
  }

  @Test void completionDuringReplayFollowsSnapshotWithoutLossOrDuplication() throws Exception {
    var registry = new RunRegistry(Clock.systemUTC()); registry.register(RunRegistryTest.context("run"));
    var bus = new InMemoryAgentEventBus(); var factory = new AgentEventFactory(Clock.systemUTC());
    bus.publish(factory.runStarted("run", "first", null));
    var entered = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    var sink = new SseBridgeTest.Sink() {
      public void event(WebApiEventDto event) throws Exception {
        events.add(event);
        if (event.sequence() == 1) { entered.countDown(); release.await(); }
      }
    };
    try (var bridge = new SseBridge(bus, new RunService(registry), "")) {
      bridge.connect("run", sink);
      try {
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        bus.publish(factory.runStarted("other", "other", null));
        bus.publish(factory.runCompleted("run", 1));
      } finally { release.countDown(); }
      assertThat(sink.ended.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(sink.events).extracting(WebApiEventDto::sequence).containsExactly(1L, 3L);
    }
  }
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
