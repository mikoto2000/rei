package dev.mikoto2000.rei.event;

/**
 * Agent Skill の選定失敗。
 *
 * @param selectionId 選択内容識別子
 * @param error エラー
 */
public record SkillSelectionFailedPayload(String selectionId, ErrorInformation error)
    implements AgentEventPayload {}
