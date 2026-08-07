package dev.mikoto2000.rei.skills;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

class AgentSkillExplicitSelectorTest {

  @Test
  void extractsSkillTokenAndRemovesItFromPrompt() {
    AgentSkill sample = skill("sample", true);
    AgentSkillExplicitSelector selector = new AgentSkillExplicitSelector(repository(sample));

    AgentSkillExplicitSelector.ExplicitSelection selection = selector.select("@skill:sample hello");

    assertThat(selection.skills()).containsExactly(sample);
    assertThat(selection.sanitizedPrompt()).isEqualTo("hello");
    assertThat(selection.warnings()).isEmpty();
  }

  @Test
  void extractsMultipleSkillsInOrder() {
    AgentSkill first = skill("first", true);
    AgentSkill second = skill("second", true);
    AgentSkillExplicitSelector selector = new AgentSkillExplicitSelector(repository(first, second));

    AgentSkillExplicitSelector.ExplicitSelection selection = selector.select("@skill:first @skill:second run");

    assertThat(selection.skills()).containsExactly(first, second);
    assertThat(selection.sanitizedPrompt()).isEqualTo("run");
  }

  @Test
  void warnsWhenSkillIsMissingOrDisabled() {
    AgentSkill disabled = skill("disabled", false);
    AgentSkillExplicitSelector selector = new AgentSkillExplicitSelector(repository(disabled));

    AgentSkillExplicitSelector.ExplicitSelection selection = selector.select("@skill:missing @skill:disabled run");

    assertThat(selection.skills()).isEmpty();
    assertThat(selection.sanitizedPrompt()).isEqualTo("run");
    assertThat(selection.warnings()).containsExactly(
        "[warn] Skill が見つかりません: missing",
        "[warn] Skill は無効です: disabled");
  }

  private AgentSkill skill(String name, boolean enabled) {
    return new AgentSkill(name, name + " description", enabled, Path.of(name), Path.of(name).resolve("SKILL.md"),
        name + " instructions");
  }

  private AgentSkillRepository repository(AgentSkill... skills) {
    return new InMemoryAgentSkillRepository(List.of(skills));
  }
}
