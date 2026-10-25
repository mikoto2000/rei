package dev.mikoto2000.rei.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 入力先記憶エージェント実行状況を共有する通知先の結果と状態の契約を検証するテスト。 */
class InMemoryAgentEventBusTest {

  @Test
  void failedPersistenceIsNeverDeliveredOrReplayedAndQueuedReentryIsCleared() {
    var bus = new InMemoryAgentEventBus();
    var displayed = new ArrayList<AgentEvent>();
    bus.subscribe(displayed::add);
    var persistence =
        bus.subscribePersistence(
            event -> {
              bus.publish(event("agent.run.completed"));
              throw new IllegalStateException("storage failed");
            });
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalStateException.class, () -> bus.publish(event("agent.run.started")));
    assertTrue(displayed.isEmpty());
    var replay = bus.subscribe("run-1", 0, ignored -> {});
    assertTrue(replay.replay().isEmpty());
    replay.subscription().unsubscribe();
    persistence.unsubscribe();
    bus.publish(event("agent.run.failed"));
    assertEquals(
        List.of("agent.run.failed"), displayed.stream().map(e -> e.type().value()).toList());
  }

  /** 日時や期限の条件を再現するために使う検証時刻。 */
  private final Clock clock =
      Clock.fixed(Instant.parse("2026-08-16T16:30:20Z"), ZoneId.of("Asia/Tokyo"));

  /** 検証で使用するイベントを、指定された条件に合わせて用意する。 */
  private AgentEvent event(String type) {
    return new AgentEvent(
        "id-" + type,
        0L,
        Instant.now(clock),
        AgentEventType.from(type),
        1,
        "session-1",
        "turn-1",
        "run-1",
        (String) null,
        (String) null,
        null);
  }

  /** 実行状況の通知で、状態の変化と結果を、通知・表示の契約に従って伝えることを確認する。 */
  @Test
  void listenerReceivesPublishedEvent() {
    InMemoryAgentEventBus bus = new InMemoryAgentEventBus();
    List<AgentEvent> received = new ArrayList<>();
    bus.subscribe(received::add);

    AgentEvent event = event("agent.run.started");
    bus.publish(event);

    assertEquals(1, received.size());
    assertEquals(event.id(), received.get(0).id());
    assertEquals(event.type(), received.get(0).type());
  }

  /** 実行状況の通知で、状態の変化と結果を、通知・表示の契約に従って伝えることを確認する。 */
  @Test
  void multipleListenersReceiveEvent() {
    InMemoryAgentEventBus bus = new InMemoryAgentEventBus();
    List<AgentEvent> first = new ArrayList<>();
    List<AgentEvent> second = new ArrayList<>();
    bus.subscribe(first::add);
    bus.subscribe(second::add);

    AgentEvent event = event("agent.run.started");
    bus.publish(event);

    assertEquals(1, first.size());
    assertEquals(1, second.size());
    assertEquals(event.id(), first.get(0).id());
    assertEquals(event.id(), second.get(0).id());
  }

  /** 実行状況の通知で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void unsubscribedListenerDoesNotReceive() {
    InMemoryAgentEventBus bus = new InMemoryAgentEventBus();
    List<AgentEvent> received = new ArrayList<>();
    AgentEventBus.Subscription subscription = bus.subscribe(received::add);

    subscription.unsubscribe();
    bus.publish(event("agent.run.started"));

    assertTrue(received.isEmpty());
  }

  /** 実行状況の通知で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void sequenceIsMonotonicallyIncreasing() {
    InMemoryAgentEventBus bus = new InMemoryAgentEventBus();
    List<AgentEvent> received = new ArrayList<>();
    bus.subscribe(received::add);

    bus.publish(event("agent.run.started"));
    bus.publish(event("message.started"));
    bus.publish(event("message.completed"));

    assertEquals(3, received.size());
    assertTrue(received.get(0).sequence() < received.get(1).sequence());
    assertTrue(received.get(1).sequence() < received.get(2).sequence());
  }

  /** 実行状況の通知で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void sequenceDoesNotDuplicate() {
    InMemoryAgentEventBus bus = new InMemoryAgentEventBus();
    List<AgentEvent> received = new ArrayList<>();
    bus.subscribe(received::add);

    for (int i = 0; i < 100; i++) {
      bus.publish(event("message.delta"));
    }

    long distinct = received.stream().map(AgentEvent::sequence).distinct().count();
    assertEquals(100, distinct);
  }

  /** 実行状況の通知で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void listenerExceptionDoesNotStopOtherListeners() {
    InMemoryAgentEventBus bus = new InMemoryAgentEventBus();
    List<AgentEvent> received = new ArrayList<>();
    bus.subscribe(
        e -> {
          throw new RuntimeException("boom");
        });
    bus.subscribe(received::add);

    bus.publish(event("agent.run.started"));

    assertEquals(1, received.size());
  }

  /** 実行状況の通知で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void publishAssignsSequence() {
    InMemoryAgentEventBus bus = new InMemoryAgentEventBus();
    List<AgentEvent> received = new ArrayList<>();
    bus.subscribe(received::add);

    bus.publish(event("agent.run.started"));

    assertNotEquals(0L, received.get(0).sequence());
    assertEquals(received.get(0).sequence(), bus.lastSequence());
  }

  /** 実行状況の通知で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void lastSequenceIsZeroBeforeFirstPublish() {
    assertEquals(0L, new InMemoryAgentEventBus().lastSequence());
  }
}
