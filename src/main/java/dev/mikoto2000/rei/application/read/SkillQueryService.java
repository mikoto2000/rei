package dev.mikoto2000.rei.application.read;

import dev.mikoto2000.rei.skills.AgentSkill;
import dev.mikoto2000.rei.skills.AgentSkillRepository;
import java.util.List;
import java.util.Optional;

/**
 * 情報の照会で受け渡すスキル検索条件サービスを表す。
 *
 * <p>Shared shell/Web read boundary; deliberately has no reload or filesystem operation.
 *
 * @param repository 保存と検索の窓口
 */
public record SkillQueryService(AgentSkillRepository repository) {
  /** 条件に合う対象を一覧にして、呼び出し元が参照できるようにする。 */
  public List<AgentSkill> list() {
    return repository.findAll();
  }

  /** 対象を特定できるよう、スキル検索条件サービスを探す。 */
  public Optional<AgentSkill> find(String name) {
    return repository.findByName(name);
  }
}
