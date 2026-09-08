package dev.mikoto2000.rei.event;

import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AgentEventDeliveryOrderTest {
  @Test void reentrantPublishDoesNotOvertakeItsParentEvent() {
    var bus = new InMemoryAgentEventBus();
    var factory = new AgentEventFactory(Clock.systemUTC());
    List<Long> delivered = new ArrayList<>();
    bus.subscribe(event -> { if (event.sequence() == 1) bus.publish(factory.messageStarted("nested", "assistant")); });
    bus.subscribe(event -> delivered.add(event.sequence()));
    bus.publish(factory.messageStarted("first", "assistant"));
    assertThat(delivered).containsExactly(1L, 2L);
  }
  @Test void concurrentPublishersDeliverEventsInAssignedSequenceOrder() throws Exception {
    var bus = new InMemoryAgentEventBus();
    var factory = new AgentEventFactory(Clock.systemUTC());
    List<Long> delivered = Collections.synchronizedList(new ArrayList<>());
    bus.subscribe(event -> { Thread.yield(); delivered.add(event.sequence()); });
    var start = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var tasks = new ArrayList<Future<?>>();
      for (int i = 0; i < 500; i++) tasks.add(executor.submit(() -> {
        start.await(); bus.publish(factory.messageStarted(UUID.randomUUID().toString(), "assistant")); return null;
      }));
      start.countDown();
      for (var task : tasks) task.get(10, TimeUnit.SECONDS);
    }
    assertThat(delivered).containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(1, 500).boxed().toList());
  }
}
