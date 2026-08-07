package dev.mikoto2000.rei.skills;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

class AgentSkillPromptRendererTest {

  @Test
  void injectsSelectedSkillInstructionsIntoPrompt() {
    AgentSkillPromptRenderer renderer = new AgentSkillPromptRenderer();
    AgentSkill skill = new AgentSkill("sample", "Sample description", true, Path.of("sample"),
        Path.of("sample/SKILL.md"), "Sample instructions");

    String rendered = renderer.render("hello", List.of(skill));

    assertThat(rendered).contains("## Skill: sample");
    assertThat(rendered).contains("Description: Sample description");
    assertThat(rendered).contains("Sample instructions");
    assertThat(rendered).contains("--- User request ---");
    assertThat(rendered).endsWith("hello");
  }

  @Test
  void returnsOriginalPromptWhenNoSkillSelected() {
    AgentSkillPromptRenderer renderer = new AgentSkillPromptRenderer();

    assertThat(renderer.render("hello", List.of())).isEqualTo("hello");
  }
}
