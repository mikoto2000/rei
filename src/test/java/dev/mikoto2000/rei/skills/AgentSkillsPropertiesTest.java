package dev.mikoto2000.rei.skills;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AgentSkillsPropertiesTest {

  @Test
  void defaultsToEnabledWithLocalSkillsDirectory() {
    AgentSkillsProperties properties = new AgentSkillsProperties();

    assertThat(properties.isEnabled()).isTrue();
    assertThat(properties.getDirectories()).containsExactly(dev.mikoto2000.rei.core.datasource.ReiDataDirectory.current().resolve("skills").toString());
    assertThat(properties.getMaxSelected()).isEqualTo(3);
  }

  @Test
  void maxSelectedIsAtLeastOne() {
    AgentSkillsProperties properties = new AgentSkillsProperties();
    properties.setMaxSelected(0);

    assertThat(properties.getMaxSelected()).isEqualTo(1);
  }
}
