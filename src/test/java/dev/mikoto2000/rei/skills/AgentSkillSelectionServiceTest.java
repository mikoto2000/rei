package dev.mikoto2000.rei.skills;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class AgentSkillSelectionServiceTest {

  @Test
  void measuresImplicitSelectorWithMonotonicClock() {
    AgentSkillsProperties properties = new AgentSkillsProperties();
    AgentSkillExplicitSelector explicitSelector = Mockito.mock(AgentSkillExplicitSelector.class);
    AgentSkillImplicitSelection implicitSelector = Mockito.mock(AgentSkillImplicitSelection.class);
    when(explicitSelector.select("hello")).thenReturn(
        new AgentSkillExplicitSelector.ExplicitSelection(List.of(), List.of(), "hello"));
    when(implicitSelector.select("hello", Set.of())).thenReturn(List.of(skill("implicit")));
    java.util.concurrent.atomic.AtomicLong nanos = new java.util.concurrent.atomic.AtomicLong();
    AgentSkillSelectionService service = new AgentSkillSelectionService(properties, explicitSelector,
        implicitSelector, () -> nanos.getAndAdd(23_000_000L));

    AgentSkillSelection selection = service.select("hello");

    assertThat(selection.selectorDurationMs()).isEqualTo(23L);
  }

  @Test
  void explicitSelectionTakesPrecedenceOverImplicitSelection() {
    AgentSkill explicit = skill("explicit");
    AgentSkill implicit = skill("implicit");
    AgentSkillsProperties properties = new AgentSkillsProperties();
    AgentSkillSelectionService service = new AgentSkillSelectionService(
        properties,
        new AgentSkillExplicitSelector(new InMemoryAgentSkillRepository(List.of(explicit))),
        (prompt, excludedSkillNames) -> List.of(implicit));

    AgentSkillSelection selection = service.select("@skill:explicit do it");

    assertThat(selection.selectedSkills()).containsExactly(explicit, implicit);
    assertThat(selection.sanitizedPrompt()).isEqualTo("do it");
  }

  @Test
  void removesDuplicateAndAppliesMaxSelected() {
    AgentSkill first = skill("first");
    AgentSkill second = skill("second");
    AgentSkillsProperties properties = new AgentSkillsProperties();
    properties.setMaxSelected(1);
    AgentSkillSelectionService service = new AgentSkillSelectionService(
        properties,
        new AgentSkillExplicitSelector(new InMemoryAgentSkillRepository(List.of(first))),
        (prompt, excludedSkillNames) -> List.of(first, second));

    AgentSkillSelection selection = service.select("@skill:first do it");

    assertThat(selection.selectedSkills()).containsExactly(first);
  }

  @Test
  void returnsNoSelectionWhenDisabled() {
    AgentSkillsProperties properties = new AgentSkillsProperties();
    properties.setEnabled(false);
    AgentSkillSelectionService service = new AgentSkillSelectionService(
        properties,
        new AgentSkillExplicitSelector(new InMemoryAgentSkillRepository(List.of(skill("sample")))),
        (prompt, excludedSkillNames) -> List.of(skill("implicit")));

    AgentSkillSelection selection = service.select("@skill:sample do it");

    assertThat(selection.selectedSkills()).isEmpty();
    assertThat(selection.sanitizedPrompt()).isEqualTo("@skill:sample do it");
  }

  private AgentSkill skill(String name) {
    return new AgentSkill(name, name + " description", true, Path.of(name), Path.of(name).resolve("SKILL.md"),
        name + " instructions");
  }
}
