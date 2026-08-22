package dev.mikoto2000.rei.event;

/**
 * Agent Run の正常終了。
 *
 * @param runId Agent Loop の一回の実行単位
 * @param duration 実行時間（ミリ秒）
 * @param completionTokens completion token 数（取得できない場合は {@code null}）
 * @param tokensPerSecond 回答生成速度（取得できない場合は {@code null}）
 */
public record AgentRunCompletedPayload(String runId, long duration, Long completionTokens, Double tokensPerSecond)
    implements AgentEventPayload {

  public AgentRunCompletedPayload(String runId, long duration) {
    this(runId, duration, null, null);
  }

  public AgentRunCompletedPayload(String runId, long duration, Long completionTokens) {
    this(runId, duration, completionTokens, null);
  }
}
