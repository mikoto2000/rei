package dev.mikoto2000.rei.event;

/** Agent Event を発行する Publisher。 */
public interface AgentEventPublisher {
  /** 状態や結果を購読先へ通知する。 */
  void publish(AgentEvent event);

  /**
   * 実行状況を利用先で把握できるよう、境界を通知する。
   *
   * <p>Durability-sensitive boundary: failures must reach the caller before a side effect.
   */
  default void publishBoundary(AgentEvent event) {
    publish(event);
  }
}
