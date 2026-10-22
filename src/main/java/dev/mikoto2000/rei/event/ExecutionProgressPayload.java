package dev.mikoto2000.rei.event;

import dev.mikoto2000.rei.core.stagnation.ProgressEvidence;

/**
 * 実行状況の通知で受け渡す実行進捗通知内容を表す。
 *
 * @param evidence 根拠
 * @param consecutiveNoProgressIterations consecutiveNo進捗繰り返し回数
 * @param threshold 判定の境界値
 * @param stagnationReplanCount 停滞計画の見直し件数
 * @param maxStagnationReplans 最大停滞計画の見直し回数
 * @param reason 理由
 */
public record ExecutionProgressPayload(
    ProgressEvidence evidence,
    int consecutiveNoProgressIterations,
    int threshold,
    int stagnationReplanCount,
    int maxStagnationReplans,
    String reason,
    dev.mikoto2000.rei.core.stagnation.RunExecutionContext.Diagnosis diagnosis)
    implements AgentEventPayload {
  public ExecutionProgressPayload(ProgressEvidence evidence, int consecutiveNoProgressIterations,
      int threshold, int stagnationReplanCount, int maxStagnationReplans, String reason) {
    this(evidence, consecutiveNoProgressIterations, threshold, stagnationReplanCount,
        maxStagnationReplans, reason, null);
  }
}
