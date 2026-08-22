package dev.mikoto2000.rei.event;

/** Agent Skill の選定開始。 */
public record SkillSelectionStartedPayload(String selectionId) implements AgentEventPayload {
}
