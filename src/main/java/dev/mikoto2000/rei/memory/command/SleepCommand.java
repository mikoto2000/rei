package dev.mikoto2000.rei.memory.command;

import java.util.concurrent.Callable;
import org.springframework.stereotype.Component;
import picocli.CommandLine.*;
import picocli.CommandLine.Model.CommandSpec;
import dev.mikoto2000.rei.memory.service.SleepService;
import dev.mikoto2000.rei.core.project.ProjectService;
import dev.mikoto2000.rei.core.service.CommandCancellationService;

@Component
@Command(name="sleep",description="現在 Session の未処理 Turn から長期記憶を整理します",
    subcommands={SleepCommand.Preview.class,SleepCommand.Status.class,SleepCommand.History.class,SleepCommand.Requests.class,SleepCommand.CancelRequest.class})
public class SleepCommand implements Callable<Integer> {
  private final SleepService service;
  private final MemoryCommandSupport support;
  private final ProjectService projects;
  private final CommandCancellationService cancellation;
  @Spec CommandSpec spec;
  private java.io.PrintWriter shellOutput;
  /** Picocli completion can inspect the command tree without a Spring context. */
  public SleepCommand() { this(null,null,null,null); }
  @org.springframework.beans.factory.annotation.Autowired
  public SleepCommand(SleepService service,MemoryCommandSupport support,ProjectService projects,CommandCancellationService cancellation) {
    this.service=service; this.support=support; this.projects=projects; this.cancellation=cancellation;
  }
  public void setShellOutput(java.io.PrintWriter output) { shellOutput=output; }
  private java.io.PrintWriter out() { return shellOutput==null?spec.commandLine().getOut():shellOutput; }
  @Override public Integer call() { return run(false); }
  int run(boolean preview) {
    return execute(() -> {
      support.enabled();
      String session=projects.currentSessionId(), project=projects.currentContext().id();
      cancellation.begin(Thread.currentThread());
      try {
        out().println(preview?"Sleep preview started...":"Sleep started..."); out().flush();
        return support.report(service.sleep(session,project,preview));
      } finally { cancellation.clear(); }
    });
  }
  int execute(java.util.function.Supplier<String> action) {
    try {
      if(support==null) throw new IllegalStateException("Memory runtime unavailable");
      out().println(action.get()); return 0;
    }
    catch(RuntimeException error) {
      if(dev.mikoto2000.rei.core.chat.RunCancellation.isCancellation(error)||Thread.currentThread().isInterrupted()) {
        out().println("Sleep cancelled. Checkpoint unchanged."); return 130;
      }
      out().println("[error] "+dev.mikoto2000.rei.event.CredentialRedactor.redact(error.getMessage())); return 2;
    } finally { out().flush(); }
  }
  @Command(name="preview") public static class Preview implements Callable<Integer> {
    @ParentCommand SleepCommand parent;
    public Integer call() { return parent.run(true); }
  }
  @Command(name="status") public static class Status implements Callable<Integer> {
    @ParentCommand SleepCommand parent;
    public Integer call() { return parent.execute(parent.support::status); }
  }
  @Command(name="history") public static class History implements Callable<Integer> {
    @ParentCommand SleepCommand parent;
    public Integer call() { return parent.execute(parent.support::history); }
  }
  @Command(name="requests",description="現在Projectの保存済みAuto Sleep要求を表示") public static class Requests implements Callable<Integer> {
    @ParentCommand SleepCommand parent;
    public Integer call(){return parent.execute(parent.support::autoSleepRequests);}
  }
  @Command(name="cancel-request",description="確認したrevisionの未処理要求を取り消す。稼働中Sleepは停止しない") public static class CancelRequest implements Callable<Integer> {
    @ParentCommand SleepCommand parent;
    @Parameters(index="0") String session;
    @Option(names="--revision",required=true) long revision;
    public Integer call(){return parent.execute(()->parent.support.cancelAutoSleepRequest(session,revision));}
  }
}
