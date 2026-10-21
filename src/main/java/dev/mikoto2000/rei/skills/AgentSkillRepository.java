package dev.mikoto2000.rei.skills;

import java.util.List;
import java.util.Optional;

/** エージェントスキルを保存・検索するための窓口。 */
public interface AgentSkillRepository {

  /** 対象を特定できるよう、全対象を探す。 */
  List<AgentSkill> findAll();

  /** 対象を特定できるよう、有効状態を探す。 */
  List<AgentSkill> findEnabled();

  /** 対象を特定できるよう、別名前を探す。 */
  Optional<AgentSkill> findByName(String name);

  /** 変更された設定や記録を反映するため、エージェントスキル保存と検索の窓口を読み直す。 */
  void reload();
}
