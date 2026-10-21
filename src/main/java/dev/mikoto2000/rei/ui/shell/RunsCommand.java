package dev.mikoto2000.rei.ui.shell;

import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/** 全プロジェクトの実行中AgentRunを表示しますを行うための利用者コマンド。 */
@Component
@Command(name = "runs", description = "全プロジェクトの実行中AgentRunを表示します")
public class RunsCommand implements Runnable {
  /** 表示。 */
  private final ActiveRunDisplay display;

  /** ターミナルへの表示先。 */
  private java.io.PrintWriter shellOutput;

  /** 仕様。 */
  @Spec private CommandSpec spec;

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
    this.shellOutput = output;
  }

  /** 全プロジェクトの実行中AgentRunを表示しますを行う。 */
  public void run() {
    var out = shellOutput == null ? spec.commandLine().getOut() : shellOutput;
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
