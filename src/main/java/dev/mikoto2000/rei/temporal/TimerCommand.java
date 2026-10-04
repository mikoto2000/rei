package dev.mikoto2000.rei.temporal;

import org.springframework.stereotype.Component;
import dev.mikoto2000.rei.core.project.ProjectService;
import picocli.CommandLine.*;

@Component
@Command(name="timer",description="Review, activate and inspect persistent one-shot continuations")
public class TimerCommand implements java.util.concurrent.Callable<Integer> {
  private final PersistentAgentScheduler scheduler;
  private final ProjectService projects;
  private final AgentScheduleDispatcher dispatcher;
  @Spec picocli.CommandLine.Model.CommandSpec spec;
  @Parameters(index="0",arity="0..1",defaultValue="list",paramLabel="list|show|activate|cancel|history|reconcile") String action;
  @Parameters(index="1",arity="0..1",paramLabel="timerId") String id;
  @Option(names="--run-id",description="Observed uncertain Run ID") String expectedRunId;
  @Option(names="--acknowledge-uncertain-side-effects",description="Acknowledge unknown effects; this does not replay the schedule") boolean acknowledgeUncertain;
  private java.io.PrintWriter output;
  public TimerCommand(){this(null,null,null);}
  public TimerCommand(PersistentAgentScheduler scheduler,ProjectService projects){this(scheduler,projects,null);}
  @org.springframework.beans.factory.annotation.Autowired
  public TimerCommand(PersistentAgentScheduler scheduler,ProjectService projects,AgentScheduleDispatcher dispatcher){this.scheduler=scheduler;this.projects=projects;this.dispatcher=dispatcher;}
  public void setShellOutput(java.io.PrintWriter output){this.output=output;}
  @Override public Integer call() {
    var writer=output==null?spec.commandLine().getOut():output;
    try {
      String project=projects.currentContext().id();
      Object result=switch(action) {
        case "list" -> scheduler.list(project);
        case "show" -> scheduler.get(project,requiredId());
        case "history" -> scheduler.history(project,requiredId());
        case "reconcile" -> {
          if(dispatcher==null||!acknowledgeUncertain||expectedRunId==null||expectedRunId.isBlank())throw new IllegalArgumentException("Reconcile requires Run controls, --run-id and --acknowledge-uncertain-side-effects; inspect effects before creating another schedule");
          yield dispatcher.reconcile(project,requiredId(),expectedRunId);
        }
        case "activate" -> {scheduler.activate(project,requiredId());yield "Schedule activated; dispatch requires rei.agent-scheduler.enabled and rei.tool-permission.enabled";}
        case "cancel" -> {scheduler.cancel(project,requiredId());yield "Schedule cancelled";}
        default -> throw new IllegalArgumentException("Use /timer list|show|activate|cancel|history|reconcile [timerId]");
      };
      writer.println(dev.mikoto2000.rei.event.CredentialRedactor.redact(String.valueOf(result)));return 0;
    } catch(RuntimeException error){writer.println("[error] "+dev.mikoto2000.rei.event.CredentialRedactor.redact(error.getMessage()));return 2;}
    finally {writer.flush();}
  }
  private String requiredId(){if(id==null||id.isBlank())throw new IllegalArgumentException("timerId is required");return id;}
}
