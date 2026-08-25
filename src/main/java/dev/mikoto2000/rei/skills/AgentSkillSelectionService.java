package dev.mikoto2000.rei.skills;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.LongSupplier;

import org.springframework.stereotype.Service;

@Service
public class AgentSkillSelectionService {

  private final AgentSkillsProperties properties;
  private final AgentSkillExplicitSelector explicitSelector;
  private final AgentSkillImplicitSelection implicitSelector;
  private final LongSupplier nanoTime;

  @org.springframework.beans.factory.annotation.Autowired
  public AgentSkillSelectionService(AgentSkillsProperties properties, AgentSkillExplicitSelector explicitSelector,
      AgentSkillImplicitSelection implicitSelector) {
    this(properties, explicitSelector, implicitSelector, System::nanoTime);
  }

  AgentSkillSelectionService(AgentSkillsProperties properties, AgentSkillExplicitSelector explicitSelector,
      AgentSkillImplicitSelection implicitSelector, LongSupplier nanoTime) {
    this.properties = properties;
    this.explicitSelector = explicitSelector;
    this.implicitSelector = implicitSelector;
    this.nanoTime = nanoTime;
  }

  public AgentSkillSelection select(String prompt) {
    if (!properties.isEnabled()) {
      return new AgentSkillSelection(List.of(), List.of(), List.of(), prompt == null ? "" : prompt, null);
    }

    AgentSkillExplicitSelector.ExplicitSelection explicit = explicitSelector.select(prompt);
    List<AgentSkill> explicitSkills = distinctAndLimit(explicit.skills(), properties.getMaxSelected());
    int remaining = properties.getMaxSelected() - explicitSkills.size();
    List<AgentSkill> implicitSkills = List.of();
    Long selectorDurationMs = null;
    if (remaining > 0) {
      Set<String> explicitNames = explicitSkills.stream().map(AgentSkill::name).collect(java.util.stream.Collectors.toSet());
      long selectorStartedAtNanos = nanoTime.getAsLong();
      implicitSkills = distinctAndLimit(implicitSelector.select(explicit.sanitizedPrompt(), explicitNames), remaining);
      selectorDurationMs = Math.max(0L, (nanoTime.getAsLong() - selectorStartedAtNanos) / 1_000_000L);
    }

    return new AgentSkillSelection(explicitSkills, implicitSkills, explicit.warnings(), explicit.sanitizedPrompt(),
        selectorDurationMs);
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
