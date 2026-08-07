package dev.mikoto2000.rei.skills;

import java.util.List;
import java.util.Optional;

class InMemoryAgentSkillRepository implements AgentSkillRepository {

  private final List<AgentSkill> skills;

  InMemoryAgentSkillRepository(List<AgentSkill> skills) {
    this.skills = skills;
  }

  @Override
  public List<AgentSkill> findAll() {
    return skills;
  }

  @Override
  public List<AgentSkill> findEnabled() {
    return skills.stream().filter(AgentSkill::enabled).toList();
  }

  @Override
  public Optional<AgentSkill> findByName(String name) {
    return skills.stream().filter(skill -> skill.name().equals(name)).findFirst();
  }

  @Override
  public void reload() {
  }
}
