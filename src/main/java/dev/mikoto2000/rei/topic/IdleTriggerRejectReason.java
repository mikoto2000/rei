package dev.mikoto2000.rei.topic;

/** 話題の管理における操作がない状態実行条件拒否理由の選択肢。 */
public enum IdleTriggerRejectReason {
  /** 機能無効状態。 */
  FEATURE_DISABLED,
  /** 操作がない状態実行条件無効状態。 */
  IDLE_TRIGGER_DISABLED,
  /** INSUFFICIENT操作がない状態。 */
  INSUFFICIENT_IDLE,
  /** エージェント使用中。 */
  AGENT_BUSY,
  /** 話題生成実行中。 */
  TOPIC_GENERATOR_RUNNING
}
