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

  /** Atomically snapshots replay and registers a live listener beyond the snapshot boundary. */
  default ReplaySubscription subscribe(String runId, long fromSequence, AgentEventListener listener) {
    throw new UnsupportedOperationException("Replay is not supported by this event bus");
  }
  record ReplaySubscription(java.util.List<AgentEvent> replay, Subscription subscription,
      long latestSequence, Long terminalSequence) {}
  default void purgeRun(String runId) {}
  default void purgeExpired() {}

  /**
   * イベントを発行する。
   *
   * @param event 発行するイベント
   */
  void publish(AgentEvent event);

  /** これまでに割り当てた最新の sequence。未発行の場合は 0。 */
  long lastSequence();

  /**
   * 購読解除を表すハンドル。
   */
  interface Subscription {
    void unsubscribe();
  }
}
