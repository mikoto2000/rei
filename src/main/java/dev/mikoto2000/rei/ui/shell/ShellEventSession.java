package dev.mikoto2000.rei.ui.shell;

import dev.mikoto2000.rei.event.AgentEventBus;
import dev.mikoto2000.rei.event.AgentEventListener;

/**
 * ターミナルでの対話に必要なシェルイベント会話を扱う。
 *
 * <p>Owns the Shell listener subscription.
 */
public final class ShellEventSession implements AutoCloseable {
  /** 購読。 */
  private final AgentEventBus.Subscription subscription;

  /** 指定された値や利用先を使って、ShellEventSessionを初期化する。 */
  public ShellEventSession(AgentEventBus bus, AgentEventListener listener) {
    subscription = bus.subscribe(listener);
  }

  /** ターミナルでの対話で利用した資源や登録を解放し、利用を終了する。 */
  @Override
  public void close() {
    subscription.unsubscribe();
  }
}
