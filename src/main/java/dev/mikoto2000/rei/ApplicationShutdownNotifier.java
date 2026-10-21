package dev.mikoto2000.rei;

import dev.mikoto2000.rei.event.AgentEventBus;
import dev.mikoto2000.rei.event.AgentEventFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

/**
 * れいの起動に必要なアプリケーション終了Notifierを扱う。
 *
 * <p>Announces shutdown before lifecycle stop and bean destruction disconnect consumers.
 */
@Component
public final class ApplicationShutdownNotifier
    implements ApplicationListener<ContextClosedEvent>, Ordered {
  /** 通知の連携先。 */
  private final AgentEventBus bus;

  /** イベント一覧。 */
  private final AgentEventFactory events;

  /** 文脈。 */
  private final ApplicationContext context;

  /** 終了の案内を通知済みかどうか。 */
  private boolean announced;

  /** 指定された値や利用先を使って、ApplicationShutdownNotifierを初期化する。 */
  public ApplicationShutdownNotifier(
      AgentEventBus bus, AgentEventFactory events, ApplicationContext context) {
    this.bus = bus;
    this.events = events;
    this.context = context;
  }

  /** れいの起動に関する開始を処理する。 */
  public synchronized void begin(String reason) {
    if (announced) return;
    announced = true;
    bus.publish(events.applicationShutdownStarted(reason));
  }

  /** 通知に応じて状態や表示を更新するため、アプリケーションイベントを受け付ける。 */
  @Override
  public void onApplicationEvent(ContextClosedEvent event) {
    if (event.getApplicationContext() == context) begin("context_closed");
  }

  /** 他の補助処理と組み合わせる際の適用順序を返す。 */
  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE;
  }
}
