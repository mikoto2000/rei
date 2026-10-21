package dev.mikoto2000.rei.skills;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** スキルの利用に必要なエージェントスキル明示的な対象の選択条件を扱う。 */
@Component
public class AgentSkillExplicitSelector {

  /** スキルトークン。 */
  private static final Pattern SKILL_TOKEN = Pattern.compile("@skill:([A-Za-z0-9._-]+)");

  /** 保存と検索の窓口。 */
  private final AgentSkillRepository repository;

  /** 指定された値や利用先を使って、AgentSkillExplicitSelectorを初期化する。 */
  public AgentSkillExplicitSelector(AgentSkillRepository repository) {
    this.repository = repository;
  }

  /** 指定された条件に応じて、利用する対象を選ぶ。 */
  public ExplicitSelection select(String prompt) {
    String source = prompt == null ? "" : prompt;
    Matcher matcher = SKILL_TOKEN.matcher(source);
    List<AgentSkill> skills = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    while (matcher.find()) {
      String name = matcher.group(1);
      repository
          .findByName(name)
          .ifPresentOrElse(
              skill -> {
                if (skill.enabled()) {
                  skills.add(skill);
                } else {
                  warnings.add("[warn] Skill は無効です: " + name);
                }
              },
              () -> warnings.add("[warn] Skill が見つかりません: " + name));
    }
    String sanitizedPrompt = matcher.replaceAll("").replaceAll("\\s+", " ").strip();
    return new ExplicitSelection(List.copyOf(skills), List.copyOf(warnings), sanitizedPrompt);
  }

  /**
   * スキルの利用で受け渡す明示的な選択内容を表す。
   *
   * @param skills スキル一覧
   * @param warnings 注意事項
   * @param sanitizedPrompt 利用可能な形に整えたプロンプト
   */
  public record ExplicitSelection(
      List<AgentSkill> skills, List<String> warnings, String sanitizedPrompt) {}
}
