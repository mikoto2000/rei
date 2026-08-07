package dev.mikoto2000.rei.skills;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AgentSkillsPropertiesTest {

  @Test
  void defaultsToEnabledWithLocalSkillsDirectory() {
    AgentSkillsProperties properties = new AgentSkillsProperties();

    assertThat(properties.isEnabled()).isTrue();
    assertThat(properties.getDirectories()).containsExactly("${user.dir}/.rei/skills");
    assertThat(properties.getMaxSelected()).isEqualTo(3);
  }

  @Test
  void maxSelectedIsAtLeastOne() {
    AgentSkillsProperties properties = new AgentSkillsProperties();
    properties.setMaxSelected(0);

    assertThat(properties.getMaxSelected()).isEqualTo(1);
  }
}
