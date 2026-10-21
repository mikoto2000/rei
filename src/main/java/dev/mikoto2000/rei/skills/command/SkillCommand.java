package dev.mikoto2000.rei.skills.command;

import dev.mikoto2000.rei.skills.AgentSkill;
import dev.mikoto2000.rei.skills.AgentSkillRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

/** Agent Skills を操作しますを行うための利用者コマンド。 */
@Component
@Command(
    name = "skill",
    description = "Agent Skills を操作します",
    subcommands = {
      SkillCommand.ListCommand.class,
      SkillCommand.ShowCommand.class,
      SkillCommand.ReloadCommand.class
    })
public class SkillCommand implements Runnable {

  /** 保存と検索の窓口。 */
  private final AgentSkillRepository repository;

  /** レポート出力。 */
  private java.io.PrintWriter reportOutput;

  /** スキルの利用で用いるレポートを返す。 */
  private String report(java.util.function.Consumer<SkillCommand> action) {
    var text = new java.io.StringWriter();
    var command = new SkillCommand(repository);
    command.reportOutput = new java.io.PrintWriter(text);
    action.accept(command);
    command.reportOutput.flush();
    return text.toString();
  }

  /** 利用者が内容を確認できるよう、一覧を表示する。 */
  public String renderList() {
    return report(SkillCommand::list);
  }

  /** 利用者が内容を確認できるよう、表示を表示する。 */
  public String renderShow(String name) {
    return report(command -> command.show(name));
  }

  /** 利用者が内容を確認できるよう、再読み込みを表示する。 */
  public String renderReload() {
    return report(SkillCommand::reload);
  }

  /** 仕様。 */
  @Spec CommandSpec spec;

  /** スキルの利用を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public SkillCommand() {
    this(new EmptyAgentSkillRepository());
  }

  /** 指定された値や利用先を使って、SkillCommandを初期化する。 */
  @Autowired
  public SkillCommand(AgentSkillRepository repository) {
    this.repository = repository;
  }

  /** Agent Skills を操作しますを行う。 */
  @Override
  public void run() {
    list();
  }

  /** 条件に合う対象を一覧にして、呼び出し元が参照できるようにする。 */
  void list() {
    java.util.List<AgentSkill> skills =
        new dev.mikoto2000.rei.application.read.SkillQueryService(repository).list();
    if (skills.isEmpty()) {
      out().println("Agent Skills は登録されていません");
      return;
    }
    for (AgentSkill skill : skills) {
      out()
          .printf(
              "%s | %s | %s | %s%n",
              skill.name(),
              skill.enabled() ? "enabled" : "disabled",
              skill.description(),
              skill.directory());
    }
  }

  /** 利用者が対象の情報を確認できるように表示する。 */
  void show(String name) {
    new dev.mikoto2000.rei.application.read.SkillQueryService(repository)
        .find(name)
        .ifPresentOrElse(
            skill -> {
              out().println("name: " + skill.name());
              out().println("enabled: " + skill.enabled());
              out().println("description: " + skill.description());
              out().println("directory: " + skill.directory());
              out().println("skillFile: " + skill.skillFile());
              out().println();
              out().println(skill.instructions());
            },
            () -> out().println("[error] Skill が見つかりません: " + name));
  }

  /** 変更された設定や記録を反映するため、スキルコマンドを読み直す。 */
  void reload() {
    int count = new dev.mikoto2000.rei.application.state.SkillReloadService(repository).reload();
    out().println("Agent Skills を再読み込みしました: " + count + " 件");
  }

  /** スキルの利用で用いる出力先を返す。 */
  private java.io.PrintWriter out() {
    return reportOutput == null ? spec.commandLine().getOut() : reportOutput;
  }

  /** Agent Skills の一覧を表示しますを行うための利用者コマンド。 */
  @Command(name = "list", description = "Agent Skills の一覧を表示します")
  public static class ListCommand implements Runnable {
    /** 親。 */
    @ParentCommand SkillCommand parent;

    /** Agent Skills の一覧を表示しますを行う。 */
    @Override
    public void run() {
      parent.list();
    }
  }

  /** Agent Skill の詳細を表示しますを行うための利用者コマンド。 */
  @Command(name = "show", description = "Agent Skill の詳細を表示します")
  public static class ShowCommand implements Runnable {
    /** 親。 */
    @ParentCommand SkillCommand parent;

    /** 名前。 */
    @Parameters(index = "0", paramLabel = "NAME")
    String name;

    /** Agent Skill の詳細を表示しますを行う。 */
    @Override
    public void run() {
      parent.show(name);
    }
  }

  /** Agent Skills を再読み込みしますを行うための利用者コマンド。 */
  @Command(name = "reload", description = "Agent Skills を再読み込みします")
  public static class ReloadCommand implements Runnable {
    /** 親。 */
    @ParentCommand SkillCommand parent;

    /** Agent Skills を再読み込みしますを行う。 */
    @Override
    public void run() {
      parent.reload();
    }
  }

  /** 空エージェントスキルを保存・検索するための窓口。 */
  private static class EmptyAgentSkillRepository implements AgentSkillRepository {
    /** 対象を特定できるよう、全対象を探す。 */
    @Override
    public java.util.List<AgentSkill> findAll() {
      return java.util.List.of();
    }

    /** 対象を特定できるよう、有効状態を探す。 */
    @Override
    public java.util.List<AgentSkill> findEnabled() {
      return java.util.List.of();
    }

    /** 対象を特定できるよう、別名前を探す。 */
    @Override
    public java.util.Optional<AgentSkill> findByName(String name) {
      return java.util.Optional.empty();
    }

    /** 変更された設定や記録を反映するため、空エージェントスキル保存と検索の窓口を読み直す。 */
    @Override
    public void reload() {}
  }
}
