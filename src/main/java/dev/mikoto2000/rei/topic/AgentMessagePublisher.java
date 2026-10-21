package dev.mikoto2000.rei.topic;

/** 話題の管理に必要なエージェントメッセージ通知先を扱う。 */
public interface AgentMessagePublisher {
  /** 状態や結果を購読先へ通知する。 */
  void publish(AgentMessage message);
}
