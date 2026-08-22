package dev.mikoto2000.rei.event;

/** Agent Skill の選定失敗。 */
public record SkillSelectionFailedPayload(String selectionId, ErrorInformation error) implements AgentEventPayload {
}
