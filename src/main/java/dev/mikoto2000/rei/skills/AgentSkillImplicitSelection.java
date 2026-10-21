package dev.mikoto2000.rei.skills;

import java.util.List;
import java.util.Set;

/** スキルの利用に必要なエージェントスキル暗黙の選択内容を扱う。 */
@FunctionalInterface
public interface AgentSkillImplicitSelection {

  /** 指定された条件に応じて、利用する対象を選ぶ。 */
  List<AgentSkill> select(
      String prompt, Set<String> excludedSkillNames, List<AgentSkill> candidates);

  /** 指定された条件に応じて、利用する対象を選ぶ。 */
  default List<AgentSkill> select(
      String prompt,
      Set<String> excludedSkillNames,
      List<AgentSkill> candidates,
      Runnable beforeModelCall) {
    if (beforeModelCall != null) beforeModelCall.run();
    return select(prompt, excludedSkillNames, candidates);
  }
}
