package dev.mikoto2000.rei.skills;

import java.util.List;
import java.util.Optional;

/** 検証条件を再現するための入力先記憶エージェントスキル保存と検索の窓口。 */
class InMemoryAgentSkillRepository implements AgentSkillRepository {

  /** 検証に使うスキル一覧。 */
  private final List<AgentSkill> skills;

  /** 検証条件に必要な値を受け取り、InMemoryAgentSkillRepositoryを用意する。 */
  InMemoryAgentSkillRepository(List<AgentSkill> skills) {
    this.skills = skills;
  }

  /** 検証用に、対象を特定できるよう、全対象を探す。 */
  @Override
  public List<AgentSkill> findAll() {
    return skills;
  }

  /** 検証用に、対象を特定できるよう、有効状態を探す。 */
  @Override
  public List<AgentSkill> findEnabled() {
    return skills.stream().filter(AgentSkill::enabled).toList();
  }

  /** 検証用に、対象を特定できるよう、別名前を探す。 */
  @Override
  public Optional<AgentSkill> findByName(String name) {
    return skills.stream().filter(skill -> skill.name().equals(name)).findFirst();
  }

  /** {@code reload} の条件を再現するため、検証用の操作や状態を扱う。 */
  @Override
  public void reload() {}
}
