package dev.mikoto2000.rei.event;

/**
 * Agent Skill の選定開始。
 *
 * @param selectionId 選択内容識別子
 */
public record SkillSelectionStartedPayload(String selectionId) implements AgentEventPayload {}
