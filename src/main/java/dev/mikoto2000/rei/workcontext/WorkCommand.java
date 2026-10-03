package dev.mikoto2000.rei.workcontext;
import java.util.concurrent.Callable;
import org.springframework.stereotype.Component;
import picocli.CommandLine.*;
import picocli.CommandLine.Model.CommandSpec;
import dev.mikoto2000.rei.core.project.ProjectService;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.core.chat.RunCancellation;

@Component
@Command(name="work",description="プロジェクトの作業引き継ぎ",subcommands={WorkCommand.Show.class,WorkCommand.Update.class,WorkCommand.History.class})
public class WorkCommand implements Callable<Integer> {
  private final WorkContextService service;private final ProjectService projects;
  private final WorkContextGit git;private final CommandCancellationService cancellation;
  @Spec CommandSpec spec;private java.io.PrintWriter shellOutput;
  public WorkCommand() {this(null,null,null,null);}
  @org.springframework.beans.factory.annotation.Autowired
  public WorkCommand(WorkContextService service,ProjectService projects,WorkContextGit git,CommandCancellationService cancellation) {
    this.service=service;this.projects=projects;this.git=git;this.cancellation=cancellation;
  }
  public void setShellOutput(java.io.PrintWriter writer) {shellOutput=writer;}
  private java.io.PrintWriter out() {return shellOutput==null?spec.commandLine().getOut():shellOutput;}
  @Override public Integer call() {return show();}
  int show() {return execute(()->{
    var p=projects.currentContext();return new WorkContextFormatter().details(service.current(p.id()).orElse(null),git.capture(p.root(),java.time.Instant.now()));
  });}
  int update() {return execute(()->{
    // Capture session first; the application service resolves the immutable session owner.
    String session=projects.currentSessionId();cancellation.begin(Thread.currentThread());
    try {return service.update(session,null,true).map(c->"Work Context saved: project="+c.projectId()+" revision="+c.revision()).orElse("保存できる会話はまだありません。");}
    finally {cancellation.clear();}
  });}
  int history() {return execute(()->{
    var rows=service.history(projects.currentContext().id(),100);if(rows.isEmpty()) return "Work Context: 履歴はまだありません。";
    return rows.stream().map(c->"revision="+c.revision()+" updated="+c.updatedAt()+" items="+c.items().size()).collect(java.util.stream.Collectors.joining("\n"));
  });}
  int execute(java.util.function.Supplier<String> action) {
    try {if(service==null) throw new IllegalStateException("Work Context runtime unavailable");out().println(action.get());return 0;}
    catch(RuntimeException error) {
      if(RunCancellation.isCancellation(error)||Thread.currentThread().isInterrupted()) {out().println("Work Context update cancelled. Saved state retained.");return 130;}
      out().println("[error] "+dev.mikoto2000.rei.event.CredentialRedactor.redact(error.getMessage()));return 2;
    } finally {out().flush();}
  }
  @Command(name="show") public static class Show implements Callable<Integer> {@ParentCommand WorkCommand parent;public Integer call(){return parent.show();}}
  @Command(name="update") public static class Update implements Callable<Integer> {@ParentCommand WorkCommand parent;public Integer call(){return parent.update();}}
  @Command(name="history") public static class History implements Callable<Integer> {@ParentCommand WorkCommand parent;public Integer call(){return parent.history();}}
}
