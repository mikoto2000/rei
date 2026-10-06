package dev.mikoto2000.rei.skills;

import java.util.List;
import java.util.Set;

@FunctionalInterface
public interface AgentSkillImplicitSelection {

  List<AgentSkill> select(String prompt, Set<String> excludedSkillNames, List<AgentSkill> candidates);

  default List<AgentSkill> select(String prompt, Set<String> excludedSkillNames,
      List<AgentSkill> candidates, Runnable beforeModelCall) {
    if (beforeModelCall != null) beforeModelCall.run();
    return select(prompt, excludedSkillNames, candidates);
  }
}
