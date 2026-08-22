package dev.mikoto2000.rei.event;

import java.util.List;

/** Agent Skill の選定結果。 */
public record SkillSelectionCompletedPayload(
    String selectionId,
    List<String> explicitSkillNames,
    List<String> implicitSkillNames,
    List<String> warnings) implements AgentEventPayload {

  public SkillSelectionCompletedPayload {
    explicitSkillNames = explicitSkillNames == null ? List.of() : List.copyOf(explicitSkillNames);
    implicitSkillNames = implicitSkillNames == null ? List.of() : List.copyOf(implicitSkillNames);
    warnings = warnings == null ? List.of() : List.copyOf(warnings);
  }
}
