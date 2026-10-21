package dev.mikoto2000.rei.event;

/**
 * 実行状況の通知で受け渡す注意必須通知内容を表す。
 *
 * @param attentionId 注意識別子
 * @param kind 種類
 * @param message メッセージ
 */
public record AttentionRequiredPayload(String attentionId, String kind, String message)
    implements AgentEventPayload {}
