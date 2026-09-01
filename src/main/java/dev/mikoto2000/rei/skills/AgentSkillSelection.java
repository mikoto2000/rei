package dev.mikoto2000.rei.skills;

import java.util.List;

public record AgentSkillSelection(
    List<AgentSkill> explicitSkills,
    List<AgentSkill> implicitSkills,
    List<String> warnings,
    String sanitizedPrompt,
    Long selectorDurationMs,
    List<SkillCandidate> candidates,
    Long candidateDurationMs) {

  public AgentSkillSelection {
    candidates = candidates == null ? List.of() : List.copyOf(candidates);
  }

  public AgentSkillSelection(List<AgentSkill> explicitSkills, List<AgentSkill> implicitSkills,
      List<String> warnings, String sanitizedPrompt) {
    this(explicitSkills, implicitSkills, warnings, sanitizedPrompt, null, List.of(), null);
  }

  public AgentSkillSelection(List<AgentSkill> explicitSkills, List<AgentSkill> implicitSkills,
      List<String> warnings, String sanitizedPrompt, Long selectorDurationMs) {
    this(explicitSkills, implicitSkills, warnings, sanitizedPrompt, selectorDurationMs, List.of(), null);
  }

  public List<AgentSkill> selectedSkills() {
    return java.util.stream.Stream.concat(explicitSkills.stream(), implicitSkills.stream()).toList();
  }

  public List<AgentSkill> candidateSkills() {
    return candidates.stream().map(SkillCandidate::skill).toList();
  }
}
