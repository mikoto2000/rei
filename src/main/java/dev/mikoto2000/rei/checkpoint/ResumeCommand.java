package dev.mikoto2000.rei.checkpoint;
import java.util.concurrent.Callable;
import org.springframework.stereotype.Component;
import picocli.CommandLine.*;
import picocli.CommandLine.Model.CommandSpec;
import dev.mikoto2000.rei.core.project.ProjectService;
import dev.mikoto2000.rei.core.chat.AgentRunContext;

@Component
@Command(name="resume",description="保存タスクの一覧・照合・明示再開・除外",subcommands={ResumeCommand.ListTasks.class,ResumeCommand.Show.class,ResumeCommand.Abandon.class})
public class ResumeCommand implements Callable<Integer> {
  private final PersistentCheckpointService service;private final ProjectService projects;
  private final dev.mikoto2000.rei.core.service.CommandCancellationService cancellation;
  @Spec CommandSpec spec;
  @Parameters(index="0",arity="0..1",paramLabel="taskId") String task;
  private java.io.PrintWriter output;
  public ResumeCommand(){this(null,null,new dev.mikoto2000.rei.core.service.CommandCancellationService());}
  public ResumeCommand(PersistentCheckpointService service,ProjectService projects){this(service,projects,new dev.mikoto2000.rei.core.service.CommandCancellationService());}
  @org.springframework.beans.factory.annotation.Autowired
  public ResumeCommand(PersistentCheckpointService service,ProjectService projects,dev.mikoto2000.rei.core.service.CommandCancellationService cancellation){this.service=service;this.projects=projects;this.cancellation=cancellation;}
  public void setShellOutput(java.io.PrintWriter output){this.output=output;}
  int print(java.util.function.Supplier<Object> action) {
    var writer=output==null?spec.commandLine().getOut():output;
    cancellation.begin(Thread.currentThread());
    try{CheckpointReconciler.active();writer.println(action.get());return 0;}
    catch(RuntimeException e){writer.println("[error] "+dev.mikoto2000.rei.event.CredentialRedactor.redact(e.getMessage()));return Thread.currentThread().isInterrupted()?130:2;}
    finally{cancellation.clear();writer.flush();}
  }
  String project(){return projects.currentContext().id();}
  @Override public Integer call(){return print(()->task==null?service.list(project()):service.resume(project(),task,AgentRunContext.RequestSource.SHELL));}
  @Command(name="list") public static class ListTasks implements Callable<Integer>{@ParentCommand ResumeCommand parent;public Integer call(){return parent.print(()->parent.service.list(parent.project()).stream().map(s->s.taskId()+" ["+s.status()+"] "+PersistentCheckpointService.bounded(s.request(),160)).toList());}}
  @Command(name="show") public static class Show implements Callable<Integer>{@ParentCommand ResumeCommand parent;@Parameters(index="0") String task;public Integer call(){return parent.print(()->parent.service.get(parent.project(),task)+"\n"+parent.service.inspect(parent.project(),task));}}
  @Command(name="abandon") public static class Abandon implements Callable<Integer>{@ParentCommand ResumeCommand parent;@Parameters(index="0") String task;public Integer call(){return parent.print(()->parent.service.abandon(parent.project(),task));}}
}
