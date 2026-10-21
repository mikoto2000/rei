package dev.mikoto2000.rei.event;

/**
 * 実行状況の通知で受け渡す利用者追加入力通知内容を表す。
 *
 * @param interventionId 追加入力識別子
 * @param text テキスト
 */
public record UserInterventionPayload(String interventionId, String text)
    implements AgentEventPayload {}
