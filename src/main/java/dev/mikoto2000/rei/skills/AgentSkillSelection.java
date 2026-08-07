package dev.mikoto2000.rei.skills;

import java.util.List;

public record AgentSkillSelection(
    List<AgentSkill> explicitSkills,
    List<AgentSkill> implicitSkills,
    List<String> warnings,
    String sanitizedPrompt) {

  public List<AgentSkill> selectedSkills() {
    return java.util.stream.Stream.concat(explicitSkills.stream(), implicitSkills.stream()).toList();
  }
}
