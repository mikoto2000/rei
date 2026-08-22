package dev.mikoto2000.rei.event;

/**
 * Agent Event Bus。
 *
 * <p>複数 Listener の登録・購読解除・イベント発行を担う。
 * 同一プロセス内で完結する。</p>
 */
public interface AgentEventBus {

  /**
   * Listener を購読する。
   *
   * @param listener 購読する Listener
   * @return 購読解除用の Subscription
   */
  Subscription subscribe(AgentEventListener listener);

  /**
   * イベントを発行する。
   *
   * @param event 発行するイベント
   */
  void publish(AgentEvent event);

  /**
   * 購読解除を表すハンドル。
   */
  interface Subscription {
    void unsubscribe();
  }
}
