package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.application.run.*;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.event.*;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class RunLifecycleTest {
  @Test void executionUpdatesRegistryAndRecoversMissingTerminalEvents() {
    var registry = new RunRegistry(Clock.systemUTC());
    var bus = new InMemoryAgentEventBus();
    var factory = new AgentEventFactory(Clock.systemUTC());
    var service = new RunService(registry, bus, factory, new CommandCancellationService(), id -> false);
    for (String id : List.of("completed", "silent", "throws")) registry.register(RunRegistryTest.context(id));
    service.execute(RunRegistryTest.context("completed"), () -> {
      assertThat(service.get("completed").status()).isEqualTo(RunStatus.RUNNING);
      bus.publish(factory.runCompleted("completed", 1));
    });
    service.execute(RunRegistryTest.context("silent"), () -> {});
    service.execute(RunRegistryTest.context("throws"), () -> { throw new IllegalStateException("failed"); });
    assertThat(service.get("completed").status()).isEqualTo(RunStatus.COMPLETED);
    assertThat(service.get("silent").status()).isEqualTo(RunStatus.FAILED);
    assertThat(service.get("throws").status()).isEqualTo(RunStatus.FAILED);
  }

  @Test void queuedCancelIsIdempotentPublishesOnceAndNeverStartsRunner() {
    var registry = new RunRegistry(Clock.systemUTC());
    var bus = new InMemoryAgentEventBus();
    List<AgentEvent> events = new ArrayList<>();
    bus.subscribe(events::add);
    List<String> removed = new ArrayList<>();
    var service = new RunService(registry, bus, new AgentEventFactory(Clock.systemUTC()),
        new CommandCancellationService(), id -> removed.add(id));
    registry.register(RunRegistryTest.context("run"));
    assertThat(service.cancel("run").accepted()).isTrue();
    assertThat(service.cancel("run").accepted()).isFalse();
    service.execute(RunRegistryTest.context("run"), () -> { throw new AssertionError("cancelled runner started"); });
    assertThat(events).extracting(AgentEvent::type).containsExactly(AgentEventType.AGENT_RUN_CANCELLED);
    assertThat(removed).containsExactly("run");
    assertThat(service.get("run").status()).isEqualTo(RunStatus.CANCELLED);
    assertThatThrownBy(() -> service.cancel("unknown")).isInstanceOf(RunNotFoundException.class);
  }
}
