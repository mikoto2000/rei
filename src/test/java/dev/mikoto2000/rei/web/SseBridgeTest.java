package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.application.run.*;
import dev.mikoto2000.rei.event.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SseBridgeTest {
  @Test void broadcastsShutdownToAllRunsAndDrainsBeforeBridgeCloses() throws Exception {
    var registry = new RunRegistry(Clock.systemUTC());
    registry.register(RunRegistryTest.context("one"));
    registry.register(RunRegistryTest.context("two"));
    var bus = new InMemoryAgentEventBus();
    var factory = new AgentEventFactory(Clock.systemUTC());
    var first = new Sink();
    var second = new Sink();
    try (var bridge = new SseBridge(bus, new RunService(registry), "")) {
      bridge.connect("one", first);
      bridge.connect("two", second);
      bus.publish(factory.applicationShutdownStarted("context_closed"));
    }
    for (var sink : List.of(first, second)) {
      assertThat(sink.ended.getCount()).isZero();
      assertThat(sink.error).isNull();
      assertThat(sink.events).extracting(WebApiEventDto::type).containsExactly("application.shutdown.started");
      assertThat(sink.events.getFirst().payload()).containsEntry("reason", "context_closed");
      assertThat(sink.events.getFirst().runId()).isNull();
    }
    assertThat(registry.get("one").status()).isEqualTo(RunStatus.QUEUED);
  }

  @Test void connectionOpenedDuringShutdownReceivesNotification() throws Exception {
    var registry = new RunRegistry(Clock.systemUTC());
    registry.register(RunRegistryTest.context("run"));
    var bus = new InMemoryAgentEventBus();
    try (var bridge = new SseBridge(bus, new RunService(registry), "")) {
      bus.publish(new AgentEventFactory(Clock.systemUTC()).applicationShutdownStarted("shell_exit"));
      var sink = new Sink();
      bridge.connect("run", sink);
      assertThat(sink.ended.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(sink.events).extracting(WebApiEventDto::type).containsExactly("application.shutdown.started");
    }
  }
  static class Sink implements SseBridge.Sink {
    final List<WebApiEventDto> events = new CopyOnWriteArrayList<>();
    final CountDownLatch ended = new CountDownLatch(1);
    volatile Throwable error;
    public void event(WebApiEventDto event) throws Exception { events.add(event); }
    public void heartbeat() throws Exception {}
    public void complete(Throwable error) { this.error = error; ended.countDown(); }
  }
  @Test void filtersRunAndCompletesAfterSendingTerminal() throws Exception {
    var registry = new RunRegistry(Clock.systemUTC());
    registry.register(RunRegistryTest.context("run"));
    var bus = new InMemoryAgentEventBus();
    var factory = new AgentEventFactory(Clock.systemUTC());
    try (var bridge = new SseBridge(bus, new RunService(registry), "")) {
      var sink = new Sink();
      try (var connection = bridge.connect("run", sink)) {
        bus.publish(factory.runStarted("other", "test", null));
        bus.publish(factory.runStarted("run", "test", null));
        bus.publish(factory.runCompleted("run", 1));
        assertThat(sink.ended.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(sink.events).extracting(WebApiEventDto::type).containsExactly("agent.run.started", "agent.run.completed");
        assertThat(connection.isClosed()).isTrue();
      }
    }
  }
  @Test void blockedWriterDoesNotBlockPublisherAndOverflowClosesOnlyConnection() throws Exception {
    var registry = new RunRegistry(Clock.systemUTC());
    registry.register(RunRegistryTest.context("run"));
    var bus = new InMemoryAgentEventBus();
    var factory = new AgentEventFactory(Clock.systemUTC());
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var sink = new Sink() {
      public void event(WebApiEventDto event) throws Exception { entered.countDown(); release.await(); }
    };
    try (var bridge = new SseBridge(bus, new RunService(registry), "")) {
      try (var connection = bridge.connect("run", sink)) {
        bus.publish(factory.runStarted("run", "test", null));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        try {
          for (int i = 0; i < 1001; i++) bus.publish(factory.runStarted("run", "test", null));
          assertThat(sink.ended.await(5, TimeUnit.SECONDS)).isTrue();
          assertThat(sink.error).isNotNull();
          assertThat(connection.isClosed()).isTrue();
          assertThat(registry.get("run").status()).isEqualTo(RunStatus.QUEUED);
        } finally { release.countDown(); }
      }
    }
  }
}
