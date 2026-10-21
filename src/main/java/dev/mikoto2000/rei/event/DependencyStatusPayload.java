package dev.mikoto2000.rei.event;

/**
 * 実行状況の通知で受け渡す依存条件状態通知内容を表す。
 *
 * <p>Observed dependency facts, without response bodies, process logs or user answers.
 *
 * @param dependencyId 依存条件識別子
 * @param kind 種類
 * @param state 状態
 * @param reason 理由
 * @param revision 改訂番号
 */
public record DependencyStatusPayload(
    String dependencyId, String kind, String state, String reason, long revision)
    implements AgentEventPayload {}
