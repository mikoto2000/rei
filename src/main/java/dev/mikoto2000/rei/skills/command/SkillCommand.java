package dev.mikoto2000.rei.skills.command;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import dev.mikoto2000.rei.skills.AgentSkill;
import dev.mikoto2000.rei.skills.AgentSkillRepository;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

@Component
@Command(name = "skill", description = "Agent Skills を操作します", subcommands = {
    SkillCommand.ListCommand.class,
    SkillCommand.ShowCommand.class,
    SkillCommand.ReloadCommand.class
})
public class SkillCommand implements Runnable {

  private final AgentSkillRepository repository;

  @Spec
  CommandSpec spec;

  public SkillCommand() {
    this(new EmptyAgentSkillRepository());
  }

  @Autowired
  public SkillCommand(AgentSkillRepository repository) {
    this.repository = repository;
  }

  @Override
  public void run() {
    list();
  }

  void list() {
    java.util.List<AgentSkill> skills = repository.findAll();
    if (skills.isEmpty()) {
      out().println("Agent Skills は登録されていません");
      return;
    }
    for (AgentSkill skill : skills) {
      out().printf("%s | %s | %s | %s%n",
          skill.name(),
          skill.enabled() ? "enabled" : "disabled",
          skill.description(),
          skill.directory());
    }
  }

  void show(String name) {
    repository.findByName(name).ifPresentOrElse(skill -> {
      out().println("name: " + skill.name());
      out().println("enabled: " + skill.enabled());
      out().println("description: " + skill.description());
      out().println("directory: " + skill.directory());
      out().println("skillFile: " + skill.skillFile());
      out().println();
      out().println(skill.instructions());
    }, () -> out().println("[error] Skill が見つかりません: " + name));
  }

  void reload() {
    repository.reload();
    out().println("Agent Skills を再読み込みしました: " + repository.findAll().size() + " 件");
  }

  private java.io.PrintWriter out() {
    return spec.commandLine().getOut();
  }

  @Command(name = "list", description = "Agent Skills の一覧を表示します")
  public static class ListCommand implements Runnable {
    @ParentCommand
    SkillCommand parent;

    @Override
    public void run() {
      parent.list();
    }
  }

  @Command(name = "show", description = "Agent Skill の詳細を表示します")
  public static class ShowCommand implements Runnable {
    @ParentCommand
    SkillCommand parent;

    @Parameters(index = "0", paramLabel = "NAME")
    String name;

    @Override
    public void run() {
      parent.show(name);
    }
  }

  @Command(name = "reload", description = "Agent Skills を再読み込みします")
  public static class ReloadCommand implements Runnable {
    @ParentCommand
    SkillCommand parent;

    @Override
    public void run() {
      parent.reload();
    }
  }

  private static class EmptyAgentSkillRepository implements AgentSkillRepository {
    @Override
    public java.util.List<AgentSkill> findAll() {
      return java.util.List.of();
    }

    @Override
    public java.util.List<AgentSkill> findEnabled() {
      return java.util.List.of();
    }

    @Override
    public java.util.Optional<AgentSkill> findByName(String name) {
      return java.util.Optional.empty();
    }

    @Override
    public void reload() {
    }
  }
}
