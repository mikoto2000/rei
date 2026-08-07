package dev.mikoto2000.rei.skills;

import java.util.List;
import java.util.Optional;

public interface AgentSkillRepository {

  List<AgentSkill> findAll();

  List<AgentSkill> findEnabled();

  Optional<AgentSkill> findByName(String name);

  void reload();
}
