package dev.mikoto2000.rei.event;

/**
 * 実行状況の通知で受け渡す外部エージェント受付から終了までの管理通知内容を表す。
 *
 * <p>Operational metadata only; no request text, findings or process logs.
 *
 * @param delegationId 委譲識別子
 * @param agent エージェント
 * @param action 操作
 * @param status 状態
 * @param summary 要約
 * @param duration 継続時間
 * @param exitCode 終了コード
 */
public record ExternalAgentLifecyclePayload(
    String delegationId,
    String agent,
    String action,
    String status,
    String summary,
    long duration,
    Integer exitCode)
    implements AgentEventPayload {}
