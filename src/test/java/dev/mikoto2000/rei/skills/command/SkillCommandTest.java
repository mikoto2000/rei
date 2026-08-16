package dev.mikoto2000.rei.skills.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;

import dev.mikoto2000.rei.skills.AgentSkill;
import dev.mikoto2000.rei.skills.AgentSkillRepository;
import picocli.CommandLine;

class SkillCommandTest {

  @Test
  void listPrintsSkills() {
    AgentSkillRepository repository = repository(List.of(skill("sample", true)));
    String output = execute(repository, "list");

    assertThat(output).contains("sample");
    assertThat(output).contains("enabled");
    assertThat(output).contains("sample description");
  }

  @Test
  void listPrintsEmptyMessageWhenNoSkillsExist() {
    String output = execute(repository(List.of()), "list");

    assertThat(output).contains("Agent Skills は登録されていません");
  }

  @Test
  void showPrintsSkillDetails() {
    String output = execute(repository(List.of(skill("sample", true))), "show", "sample");

    assertThat(output).contains("name: sample");
    assertThat(output).contains("enabled: true");
    assertThat(output).contains("sample instructions");
  }

  @Test
  void showPrintsErrorWhenSkillDoesNotExist() {
    String output = execute(repository(List.of()), "show", "missing");

    assertThat(output).contains("[error] Skill が見つかりません: missing");
  }

  @Test
  void reloadReloadsRepository() {
    AgentSkillRepository repository = Mockito.mock(AgentSkillRepository.class);
    Mockito.when(repository.findAll()).thenReturn(List.of(skill("sample", true)));

    String output = execute(repository, "reload");

    verify(repository).reload();
    assertThat(output).contains("Agent Skills を再読み込みしました: 1 件");
  }

  @Test
  void repositoryConstructorIsAutowired() throws Exception {
    assertThat(SkillCommand.class.getConstructor(AgentSkillRepository.class).isAnnotationPresent(Autowired.class))
        .isTrue();
  }

  private String execute(AgentSkillRepository repository, String... args) {
    StringWriter writer = new StringWriter();
    CommandLine commandLine = new CommandLine(new SkillCommand(repository));
    commandLine.setOut(new PrintWriter(writer, true));
    commandLine.setErr(new PrintWriter(writer, true));
    commandLine.execute(args);
    return writer.toString();
  }

  private AgentSkillRepository repository(List<AgentSkill> skills) {
    return new AgentSkillRepository() {
      @Override
      public List<AgentSkill> findAll() {
        return skills;
      }

      @Override
      public List<AgentSkill> findEnabled() {
        return skills.stream().filter(AgentSkill::enabled).toList();
      }

      @Override
      public Optional<AgentSkill> findByName(String name) {
        return skills.stream().filter(skill -> skill.name().equals(name)).findFirst();
      }

      @Override
      public void reload() {
      }
    };
  }

  private AgentSkill skill(String name, boolean enabled) {
    return new AgentSkill(name, name + " description", enabled, Path.of(name), Path.of(name).resolve("SKILL.md"),
        name + " instructions");
  }
}
