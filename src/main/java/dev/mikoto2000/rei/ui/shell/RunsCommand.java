package dev.mikoto2000.rei.ui.shell;

import dev.mikoto2000.rei.output.CommandOutput;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/** 全プロジェクトの実行中AgentRunを表示しますを行うための利用者コマンド。 */
@Component
@Command(
    name = "runs",
    description = "全プロジェクトの実行中AgentRunを表示します",
    subcommands = RunsCommand.History.class)
public class RunsCommand implements Runnable {
  /** 表示。 */
  private final ActiveRunDisplay display;

  /** ターミナルへの表示先。 */
  private CommandOutput shellOutput;

  /** 仕様。 */
  @Spec private CommandSpec spec;

  @picocli.CommandLine.Parameters(arity = "0..1", paramLabel = "manage")
  private String action;

  private dev.mikoto2000.rei.runmanagement.RunManagement management;
  private java.util.function.Function<String, String> input;

  @org.springframework.beans.factory.annotation.Autowired
  void setManagement(dev.mikoto2000.rei.runmanagement.RunManagement service) {
    management = service;
  }

  public void setReader(org.jline.reader.LineReader reader) {
    input =
        prompt -> {
          try {
            return reader.readLine(prompt);
          } catch (org.jline.reader.EndOfFileException
              | org.jline.reader.UserInterruptException exit) {
            return null;
          }
        };
  }

  /** 既定の状態でRunsCommandを初期化する。 */
  public RunsCommand() {
    this.display = null;
  }

  /** 指定された値や利用先を使って、RunsCommandを初期化する。 */
  @org.springframework.beans.factory.annotation.Autowired
  public RunsCommand(ActiveRunDisplay display) {
    this.display = display;
  }

  /**
   * 利用者へ結果を案内するターミナルの出力先を設定する。
   *
   * <p>Keep the terminal's writer independently of picocli's rebindable command spec.
   */
  public void setShellOutput(java.io.PrintWriter output) {
    this.shellOutput = CommandOutput.from(output);
  }

  @Command(name = "history", description = "保存された Run 履歴を新しい順に表示")
  public static class History implements java.util.concurrent.Callable<Integer> {
    @picocli.CommandLine.ParentCommand RunsCommand parent;

    @picocli.CommandLine.Option(
        names = "--status",
        description =
            "queued, running, waiting, waiting_approval, completed, failed, cancelled, unknown")
    String status;

    @picocli.CommandLine.Option(names = "--all", description = "全プロジェクトを対象にする")
    boolean all;

    @picocli.CommandLine.Option(
        names = "--limit",
        defaultValue = "20",
        description = "最大表示件数（正の整数）")
    int limit;

    public Integer call() {
      var out =
          parent.shellOutput == null
              ? CommandOutput.from(parent.spec.commandLine().getOut())
              : parent.shellOutput;
      try {
        var management = parent.management;
        if (management == null) throw new IllegalStateException("Run management unavailable");
        var rows = management.history(all ? null : management.currentProjectId(), status, limit);
        out.println("PROJECT  RUN_ID  STATUS  STARTED  COMPLETED  REQUEST  FAILURE");
        if (rows.isEmpty()) out.println("No matching runs.");
        for (var row : rows)
          out.println(
              row.projectName()
                  + "  "
                  + row.runId()
                  + "  "
                  + row.status()
                  + "  "
                  + row.startedAt()
                  + "  "
                  + row.completedAt()
                  + "  "
                  + row.request()
                  + "  "
                  + row.failure());
        return 0;
      } catch (RuntimeException error) {
        out.println(
            "[error] "
                + (error instanceof IllegalArgumentException
                    ? error.getMessage()
                    : "Run history unavailable"));
        return 2;
      } finally {
        out.flush();
      }
    }
  }

  /** 全プロジェクトの実行中AgentRunを表示しますを行う。 */
  public void run() {
    var out = shellOutput == null ? CommandOutput.from(spec.commandLine().getOut()) : shellOutput;
    if (action != null) {
      if (!"manage".equals(action) || input == null || management == null) {
        out.println("Usage in an interactive shell: /runs manage");
        out.flush();
        return;
      }
      try {
        dev.mikoto2000.rei.runmanagement.RunCheckpointMenu.open(
            management,
            input,
            line -> {
              out.println(line);
              out.flush();
            });
      } catch (java.io.IOException failure) {
        throw new java.io.UncheckedIOException(failure);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
      return;
    }
    if (display == null) {
      out.println("Active run runtime is unavailable.");
      out.flush();
      return;
    }
    var rows = display.rows();
    out.println("Active Executions");
    if (rows.isEmpty()) out.println("No active runs.");
    else {
      out.println("PROJECT  TYPE  STATUS  ELAPSED  REQUEST");
      rows.forEach(out::println);
    }
    out.flush();
  }
}
