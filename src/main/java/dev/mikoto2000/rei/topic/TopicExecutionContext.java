package dev.mikoto2000.rei.topic;

import java.time.Instant;

/**
 * 話題の管理で受け渡す話題実行文脈を表す。
 *
 * @param runId 実行の識別子
 * @param now 現在日時
 * @param lastUserActivityAt 直近利用者作業状況日時
 * @param lastAgentActivityAt 直近エージェント作業状況日時
 * @param agentBusy エージェント使用中
 * @param trigger 実行条件
 */
public record TopicExecutionContext(
    String runId,
    Instant now,
    Instant lastUserActivityAt,
    Instant lastAgentActivityAt,
    boolean agentBusy,
    TopicTrigger trigger) {}
