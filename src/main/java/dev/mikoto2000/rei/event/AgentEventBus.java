package dev.mikoto2000.rei.event;

/**
 * Agent Event Bus。
 *
 * <p>複数 Listener の登録・購読解除・イベント発行を担う。 同一プロセス内で完結する。
 */
public interface AgentEventBus {

  /**
   * Listener を購読する。
   *
   * @param listener 購読する Listener
   * @return 購読解除用の Subscription
   */
  Subscription subscribe(AgentEventListener listener);

  /** 実行状況の通知で用いる購読境界を返す。 */
  default Subscription subscribeBoundary(AgentEventListener listener) {
    return subscribe(listener);
  }

  /**
   * 実行状況の通知で用いる購読を返す。
   *
   * <p>Atomically snapshots replay and registers a live listener beyond the snapshot boundary.
   */
  default ReplaySubscription subscribe(
      String runId, long fromSequence, AgentEventListener listener) {
    throw new UnsupportedOperationException("Replay is not supported by this event bus");
  }

  /**
   * 実行状況の通知で受け渡す再送購読を表す。
   *
   * @param replay 再送
   * @param subscription 購読
   * @param latestSequence 最新発生順序
   * @param terminalSequence 終端発生順序
   */
  record ReplaySubscription(
      java.util.List<AgentEvent> replay,
      Subscription subscription,
      long latestSequence,
      Long terminalSequence) {}

  /** 実行状況の通知に関する保持期限を過ぎた情報の削除実行を処理する。 */
  default void purgeRun(String runId) {}

  /** 実行状況の通知に関する保持期限を過ぎた情報の削除期限切れを処理する。 */
  default void purgeExpired() {}

  /** 実行状況の通知に関する保持期限を過ぎた情報の削除期限切れを処理する。 */
  default void purgeExpired(java.util.Set<String> protectedRuns) {}

  /**
   * イベントを発行する。
   *
   * @param event 発行するイベント
   */
  void publish(AgentEvent event);

  /** これまでに割り当てた最新の sequence。未発行の場合は 0。 */
  long lastSequence();

  /** 購読解除を表すハンドル。 */
  interface Subscription {
    /** 実行状況の通知に関する購読の終了を処理する。 */
    void unsubscribe();
  }
}
