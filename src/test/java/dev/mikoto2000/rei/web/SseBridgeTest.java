package dev.mikoto2000.rei.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mikoto2000.rei.application.run.RunRegistry;
import dev.mikoto2000.rei.application.run.RunService;
import dev.mikoto2000.rei.application.run.RunStatus;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import dev.mikoto2000.rei.event.ReplayGapException;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** イベント配信連携の結果と状態の契約を検証するテスト。 */
class SseBridgeTest {
  /** Web APIによる操作で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void recoveredUnknownRunReturnsReplayGapSoClientsFetchItsStatus() {
    var registry = new RunRegistry(Clock.systemUTC());
    registry.register(RunRegistryTest.context("run"));
    registry.transition("run", RunStatus.RUNNING, null);
    registry.transition("run", RunStatus.UNKNOWN, null);
    try (var bridge = new SseBridge(new InMemoryAgentEventBus(), new RunService(registry), "")) {
      assertThatThrownBy(() -> bridge.connect("run", new Sink()))
          .isInstanceOf(ReplayGapException.class);
    }
  }

  /** Web APIによる操作で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void broadcastsShutdownToAllRunsAndDrainsBeforeBridgeCloses() throws Exception {
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
      assertThat(sink.events)
          .extracting(WebApiEventDto::type)
          .containsExactly("application.shutdown.started");
      assertThat(sink.events.getFirst().payload()).containsEntry("reason", "context_closed");
      assertThat(sink.events.getFirst().runId()).isNull();
    }
    assertThat(registry.get("one").status()).isEqualTo(RunStatus.QUEUED);
  }

  /** Web APIによる操作で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void connectionOpenedDuringShutdownReceivesNotification() throws Exception {
    var registry = new RunRegistry(Clock.systemUTC());
    registry.register(RunRegistryTest.context("run"));
    var bus = new InMemoryAgentEventBus();
    try (var bridge = new SseBridge(bus, new RunService(registry), "")) {
      bus.publish(
          new AgentEventFactory(Clock.systemUTC()).applicationShutdownStarted("shell_exit"));
      var sink = new Sink();
      bridge.connect("run", sink);
      assertThat(sink.ended.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(sink.events)
          .extracting(WebApiEventDto::type)
          .containsExactly("application.shutdown.started");
    }
  }

  /** 検証条件を再現するための出力先。 */
  static class Sink implements SseBridge.Sink {
    /** 通知された出来事と順序を検証するための記録。 */
    final List<WebApiEventDto> events = new CopyOnWriteArrayList<>();

    /** 検証に使う終了。 */
    final CountDownLatch ended = new CountDownLatch(1);

    /** 検証に使うエラー。 */
    volatile Throwable error;

    /** 検証で使用するイベントを、指定された条件に合わせて用意する。 */
    public void event(WebApiEventDto event) throws Exception {
      events.add(event);
    }

    /** {@code heartbeat} の条件を再現するため、検証用の操作や状態を扱う。 */
    public void heartbeat() throws Exception {}

    /** {@code complete} の条件を再現するため、検証用の操作や状態を扱う。 */
    public void complete(Throwable error) {
      this.error = error;
      ended.countDown();
    }
  }

  /** Web APIによる操作で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void filtersRunAndCompletesAfterSendingTerminal() throws Exception {
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
        assertThat(sink.events)
            .extracting(WebApiEventDto::type)
            .containsExactly("agent.run.started", "agent.run.completed");
        assertThat(connection.isClosed()).isTrue();
      }
    }
  }

  /** Web APIによる操作で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void blockedWriterDoesNotBlockPublisherAndOverflowClosesOnlyConnection() throws Exception {
    var registry = new RunRegistry(Clock.systemUTC());
    registry.register(RunRegistryTest.context("run"));
    var bus = new InMemoryAgentEventBus();
    var factory = new AgentEventFactory(Clock.systemUTC());
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var sink =
        new Sink() {
          /** 検証で使用するイベントを、指定された条件に合わせて用意する。 */
          public void event(WebApiEventDto event) throws Exception {
            entered.countDown();
            release.await();
          }
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
        } finally {
          release.countDown();
        }
      }
    }
  }
}
