package dev.mikoto2000.rei.skills;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class AgentSkillTest {

  @Test
  void holdsSkillMetadataAndInstructions() {
    Path directory = Path.of(".rei/skills/sample");
    Path skillFile = directory.resolve("SKILL.md");

    AgentSkill skill = new AgentSkill("sample", "description", true, directory, skillFile, "instructions");

    assertThat(skill.name()).isEqualTo("sample");
    assertThat(skill.description()).isEqualTo("description");
    assertThat(skill.enabled()).isTrue();
    assertThat(skill.directory()).isEqualTo(directory);
    assertThat(skill.skillFile()).isEqualTo(skillFile);
    assertThat(skill.instructions()).isEqualTo("instructions");
  }
}
