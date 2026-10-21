package dev.mikoto2000.rei.skills;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** エージェントスキル明示的な対象の選択条件の結果と状態の契約を検証するテスト。 */
class AgentSkillExplicitSelectorTest {

  /** スキルの利用で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void extractsSkillTokenAndRemovesItFromPrompt() {
    AgentSkill sample = skill("sample", true);
    AgentSkillExplicitSelector selector = new AgentSkillExplicitSelector(repository(sample));

    AgentSkillExplicitSelector.ExplicitSelection selection = selector.select("@skill:sample hello");

    assertThat(selection.skills()).containsExactly(sample);
    assertThat(selection.sanitizedPrompt()).isEqualTo("hello");
    assertThat(selection.warnings()).isEmpty();
  }

  /** スキルの利用で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void extractsMultipleSkillsInOrder() {
    AgentSkill first = skill("first", true);
    AgentSkill second = skill("second", true);
    AgentSkillExplicitSelector selector = new AgentSkillExplicitSelector(repository(first, second));

    AgentSkillExplicitSelector.ExplicitSelection selection =
        selector.select("@skill:first @skill:second run");

    assertThat(selection.skills()).containsExactly(first, second);
    assertThat(selection.sanitizedPrompt()).isEqualTo("run");
  }

  /** スキルの利用で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void warnsWhenSkillIsMissingOrDisabled() {
    AgentSkill disabled = skill("disabled", false);
    AgentSkillExplicitSelector selector = new AgentSkillExplicitSelector(repository(disabled));

    AgentSkillExplicitSelector.ExplicitSelection selection =
        selector.select("@skill:missing @skill:disabled run");

    assertThat(selection.skills()).isEmpty();
    assertThat(selection.sanitizedPrompt()).isEqualTo("run");
    assertThat(selection.warnings())
        .containsExactly("[warn] Skill が見つかりません: missing", "[warn] Skill は無効です: disabled");
  }

  /** {@code skill} の条件を再現するための補助情報を返す。 */
  private AgentSkill skill(String name, boolean enabled) {
    return new AgentSkill(
        name,
        name + " description",
        enabled,
        Path.of(name),
        Path.of(name).resolve("SKILL.md"),
        name + " instructions");
  }

  /** 検証で使用する保存と検索の窓口を、指定された条件に合わせて用意する。 */
  private AgentSkillRepository repository(AgentSkill... skills) {
    return new InMemoryAgentSkillRepository(List.of(skills));
  }
}
