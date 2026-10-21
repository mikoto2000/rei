package dev.mikoto2000.rei.event;

/**
 * 実行状況の通知で受け渡す補助エージェント受付から終了までの管理通知内容を表す。
 *
 * <p>No prompts, intermediate messages or raw exceptions are persisted here.
 *
 * @param parentRunId 親実行の識別子
 * @param subAgentRunId 補助エージェント実行の識別子
 * @param agentId エージェント識別子
 * @param taskSummary タスク要約
 * @param status 状態
 * @param duration 継続時間
 * @param failureReason 失敗情報理由
 * @param backendId バックエンド識別子
 * @param modelId モデルの識別子
 * @param totalTokens 使用トークン数の合計
 */
public record SubAgentLifecyclePayload(
    String parentRunId,
    String subAgentRunId,
    String agentId,
    String taskSummary,
    String status,
    long duration,
    String failureReason,
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        String backendId,
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        String modelId,
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        Integer totalTokens)
    implements AgentEventPayload {
  /** 実行状況の通知を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public SubAgentLifecyclePayload(
      String parentRunId,
      String subAgentRunId,
      String agentId,
      String taskSummary,
      String status,
      long duration,
      String failureReason) {
    this(
        parentRunId,
        subAgentRunId,
        agentId,
        taskSummary,
        status,
        duration,
        failureReason,
        null,
        null,
        null);
  }
}
