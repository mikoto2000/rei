package dev.mikoto2000.rei.skills;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;

@Service
public class AgentSkillSelectionService {

  private final AgentSkillsProperties properties;
  private final AgentSkillExplicitSelector explicitSelector;
  private final AgentSkillImplicitSelection implicitSelector;

  public AgentSkillSelectionService(AgentSkillsProperties properties, AgentSkillExplicitSelector explicitSelector,
      AgentSkillImplicitSelection implicitSelector) {
    this.properties = properties;
    this.explicitSelector = explicitSelector;
    this.implicitSelector = implicitSelector;
  }

  public AgentSkillSelection select(String prompt) {
    if (!properties.isEnabled()) {
      return new AgentSkillSelection(List.of(), List.of(), List.of(), prompt == null ? "" : prompt);
    }

    AgentSkillExplicitSelector.ExplicitSelection explicit = explicitSelector.select(prompt);
    List<AgentSkill> explicitSkills = distinctAndLimit(explicit.skills(), properties.getMaxSelected());
    int remaining = properties.getMaxSelected() - explicitSkills.size();
    List<AgentSkill> implicitSkills = List.of();
    if (remaining > 0) {
      Set<String> explicitNames = explicitSkills.stream().map(AgentSkill::name).collect(java.util.stream.Collectors.toSet());
      implicitSkills = distinctAndLimit(implicitSelector.select(explicit.sanitizedPrompt(), explicitNames), remaining);
    }

    return new AgentSkillSelection(explicitSkills, implicitSkills, explicit.warnings(), explicit.sanitizedPrompt());
  }

  private List<AgentSkill> distinctAndLimit(List<AgentSkill> skills, int limit) {
    List<AgentSkill> result = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    for (AgentSkill skill : skills) {
      if (skill == null || !seen.add(skill.name())) {
        continue;
      }
      result.add(skill);
      if (result.size() >= limit) {
        break;
      }
    }
    return List.copyOf(result);
  }
}
