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

  /** Display-only listener; durable state and execution-control listeners use subscribe. */
  default Subscription subscribeObserver(AgentEventListener listener) {
    return subscribe(listener);
  }

  /** Atomically register display-only live events beyond a replay snapshot. */
  default ReplaySubscription subscribeObserver(
      String runId, long fromSequence, AgentEventListener listener) {
    return subscribe(runId, fromSequence, listener);
  }

  /** Opt in to explicitly selected global display events at the same replay boundary. */
  default ReplaySubscription subscribeObserver(
      String runId,
      long fromSequence,
      AgentEventListener listener,
      java.util.function.Predicate<AgentEvent> global) {
    return withReplaySnapshot(
        () -> {
          var replay = subscribeObserver(runId, fromSequence, listener);
          try {
            var extra =
                subscribeObserver(
                    event -> {
                      if (global.test(event) && event.sequence() > replay.latestSequence())
                        listener.onEvent(event);
                    });
            return new ReplaySubscription(
                replay.replay(),
                () -> {
                  replay.subscription().unsubscribe();
                  extra.unsubscribe();
                },
                replay.latestSequence(),
                replay.terminalSequence());
          } catch (RuntimeException | Error failure) {
            replay.subscription().unsubscribe();
            throw failure;
          }
        });
  }

  /** Read run state and register replay against the same publication boundary. */
  default <T> T withReplaySnapshot(java.util.function.Supplier<T> action) {
    synchronized (this) {
      return action.get();
    }
  }

  /** 永続化を完了してからreplayと通常購読者へ渡す。失敗は発行元へ伝える。 */
  default Subscription subscribePersistence(AgentEventListener listener) {
    throw new UnsupportedOperationException(
        "Durable persistence is not supported by this event bus");
  }

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
