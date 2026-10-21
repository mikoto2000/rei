package dev.mikoto2000.rei.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** エージェントイベント配信順序の結果と状態の契約を検証するテスト。 */
class AgentEventDeliveryOrderTest {
  /** 実行状況の通知で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void reentrantPublishDoesNotOvertakeItsParentEvent() {
    var bus = new InMemoryAgentEventBus();
    var factory = new AgentEventFactory(Clock.systemUTC());
    List<Long> delivered = new ArrayList<>();
    bus.subscribe(
        event -> {
          if (event.sequence() == 1) bus.publish(factory.messageStarted("nested", "assistant"));
        });
    bus.subscribe(event -> delivered.add(event.sequence()));
    bus.publish(factory.messageStarted("first", "assistant"));
    assertThat(delivered).containsExactly(1L, 2L);
  }

  /** 実行状況の通知で、競合する要求でも、実行順序と作業の分離を守ることを確認する。 */
  @Test
  void concurrentPublishersDeliverEventsInAssignedSequenceOrder() throws Exception {
    var bus = new InMemoryAgentEventBus();
    var factory = new AgentEventFactory(Clock.systemUTC());
    List<Long> delivered = Collections.synchronizedList(new ArrayList<>());
    bus.subscribe(
        event -> {
          Thread.yield();
          delivered.add(event.sequence());
        });
    var start = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var tasks = new ArrayList<Future<?>>();
      for (int i = 0; i < 500; i++)
        tasks.add(
            executor.submit(
                () -> {
                  start.await();
                  bus.publish(factory.messageStarted(UUID.randomUUID().toString(), "assistant"));
                  return null;
                }));
      start.countDown();
      for (var task : tasks) task.get(10, TimeUnit.SECONDS);
    }
    assertThat(delivered)
        .containsExactlyElementsOf(
            java.util.stream.LongStream.rangeClosed(1, 500).boxed().toList());
  }
}
