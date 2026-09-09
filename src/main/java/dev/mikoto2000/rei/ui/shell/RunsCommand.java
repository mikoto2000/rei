package dev.mikoto2000.rei.ui.shell;

import org.springframework.stereotype.Component;
import picocli.CommandLine.*;
import picocli.CommandLine.Model.CommandSpec;

@Component
@Command(name = "runs", description = "全プロジェクトの実行中AgentRunを表示します")
public class RunsCommand implements Runnable {
  private final ActiveRunDisplay display;
  private java.io.PrintWriter shellOutput;
  @Spec private CommandSpec spec;
  public RunsCommand() { this.display = null; }
  @org.springframework.beans.factory.annotation.Autowired
  public RunsCommand(ActiveRunDisplay display) { this.display = display; }
  /** Keep the terminal's writer independently of picocli's rebindable command spec. */
  public void setShellOutput(java.io.PrintWriter output) { this.shellOutput = output; }
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
