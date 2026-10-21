package dev.mikoto2000.rei.topic;

import java.time.Instant;

/** 話題の管理に必要な操作がない状態話題実行条件を扱う。 */
public interface IdleTopicTrigger {
  /** 対象を判断基準に照らして評価する。 */
  IdleTriggerDecision evaluate(Instant now);
}
