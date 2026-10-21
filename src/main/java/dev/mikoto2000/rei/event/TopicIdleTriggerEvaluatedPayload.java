package dev.mikoto2000.rei.event;

import dev.mikoto2000.rei.topic.IdleTriggerRejectReason;
import java.time.Instant;

/**
 * 実行状況の通知で受け渡す話題操作がない状態実行条件評価済み通知内容を表す。
 *
 * @param accepted 受付済み
 * @param idleDurationMs 操作がない状態継続時間ミリ秒
 * @param requiredIdleMs 必須操作がない状態ミリ秒
 * @param rejectReason 拒否理由
 * @param evaluatedAt 評価済み日時
 */
public record TopicIdleTriggerEvaluatedPayload(
    boolean accepted,
    long idleDurationMs,
    long requiredIdleMs,
    IdleTriggerRejectReason rejectReason,
    Instant evaluatedAt)
    implements AgentEventPayload {}
