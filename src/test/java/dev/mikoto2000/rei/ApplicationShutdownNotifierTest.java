package dev.mikoto2000.rei;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.event.ApplicationShutdownStartedPayload;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import java.time.Clock;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.support.GenericApplicationContext;

/** アプリケーション終了Notifierの結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class ApplicationShutdownNotifierTest {
  /** れいの起動で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void contextCloseAnnouncesBeforeLifecycleStop() {
    var bus = new InMemoryAgentEventBus();
    var order = new ArrayList<String>();
    bus.subscribe(
        event -> {
          assertThat(event.type()).isEqualTo(AgentEventType.APPLICATION_SHUTDOWN_STARTED);
          assertThat(event.runId()).isNull();
          assertThat(event.projectId()).isNull();
          assertThat(event.payload())
              .isEqualTo(new ApplicationShutdownStartedPayload("context_closed"));
          order.add("event");
        });
    var context = new GenericApplicationContext();
    context.registerBean(
        ApplicationShutdownNotifier.class,
        () ->
            new ApplicationShutdownNotifier(
                bus, new AgentEventFactory(Clock.systemUTC()), context));
    context.registerBean(
        SmartLifecycle.class,
        () ->
            new SmartLifecycle() {
              /** 検証に使う実行中。 */
              private boolean running;

              /** 検証用に、要求された処理を開始する。 */
              public void start() {
                running = true;
              }

              /** 検証用に、実行中の処理を停止する。 */
              public void stop() {
                order.add("stop");
                running = false;
              }

              /** 検証用に、れいの起動において、実行中かどうかを判定する。 */
              public boolean isRunning() {
                return running;
              }
            });
    context.refresh();
    context.close();
    assertThat(order).containsExactly("event", "stop");
  }

  /** れいの起動で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void shellExitAndContextClosePublishOnlyOnce() {
    var bus = new InMemoryAgentEventBus();
    var received = new ArrayList<AgentEvent>();
    bus.subscribe(received::add);
    try (var context = new GenericApplicationContext()) {
      var notifier =
          new ApplicationShutdownNotifier(bus, new AgentEventFactory(Clock.systemUTC()), context);
      context.addApplicationListener(notifier);
      context.refresh();
      notifier.begin("shell_exit");
      context.close();
    }
    assertThat(received).hasSize(1);
    assertThat(received.getFirst().payload())
        .isEqualTo(new ApplicationShutdownStartedPayload("shell_exit"));
  }
}
