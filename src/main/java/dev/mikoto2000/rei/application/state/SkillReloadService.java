package dev.mikoto2000.rei.application.state;

import dev.mikoto2000.rei.skills.AgentSkillRepository;

/**
 * 状態変更の調整で受け渡すスキル再読み込みサービスを表す。
 *
 * <p>Only reloads the server-configured skill catalog; accepts no path and writes no skill files.
 *
 * @param repository 保存と検索の窓口
 */
public record SkillReloadService(AgentSkillRepository repository) {
  /** 変更された設定や記録を反映するため、スキル再読み込みサービスを読み直す。 */
  public int reload() {
    repository.reload();
    return repository.findAll().size();
  }
}
