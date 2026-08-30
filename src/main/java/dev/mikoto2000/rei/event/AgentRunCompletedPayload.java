package dev.mikoto2000.rei.event;

/**
 * Agent Run の正常終了。
 *
 * @param runId Agent Loop の一回の実行単位
 * @param duration 実行時間（ミリ秒）
 * @param completionTokens completion token 数（取得できない場合は {@code null}）
 * @param timeToFirstTokenMillis 最後の回答ストリームの TTFT（ミリ秒、取得できない場合は {@code null}）
 * @param outputTokensPerSecond 最後の回答ストリームの回答生成速度（取得できない場合は {@code null}）
 * @param endToEndTokensPerSecond 最後の回答ストリームの end-to-end 速度（取得できない場合は {@code null}）
 */
public record AgentRunCompletedPayload(String runId, long duration, Long completionTokens,
    Double timeToFirstTokenMillis, Double outputTokensPerSecond, Double endToEndTokensPerSecond)
    implements AgentEventPayload {

  public AgentRunCompletedPayload(String runId, long duration) {
    this(runId, duration, null, null, null, null);
  }

  public AgentRunCompletedPayload(String runId, long duration, Long completionTokens) {
    this(runId, duration, completionTokens, null, null, null);
  }

  /** 従来の生成速度だけを受け取る呼び出しとの互換用。 */
  public AgentRunCompletedPayload(String runId, long duration, Long completionTokens, Double tokensPerSecond) {
    this(runId, duration, completionTokens, null, tokensPerSecond, null);
  }
}
