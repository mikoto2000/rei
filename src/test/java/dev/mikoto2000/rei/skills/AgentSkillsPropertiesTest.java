package dev.mikoto2000.rei.skills;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** エージェントスキル一覧設定の結果と状態の契約を検証するテスト。 */
class AgentSkillsPropertiesTest {

  /** スキルの利用で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void defaultsToEnabledWithLocalSkillsDirectory() {
    AgentSkillsProperties properties = new AgentSkillsProperties();

    assertThat(properties.isEnabled()).isTrue();
    assertThat(properties.getDirectories())
        .containsExactly(
            dev.mikoto2000.rei.core.datasource.ReiDataDirectory.current()
                .resolve("skills")
                .toString());
    assertThat(properties.getMaxSelected()).isEqualTo(3);
  }

  /** スキルの利用で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void maxSelectedIsAtLeastOne() {
    AgentSkillsProperties properties = new AgentSkillsProperties();
    properties.setMaxSelected(0);

    assertThat(properties.getMaxSelected()).isEqualTo(1);
  }
}
