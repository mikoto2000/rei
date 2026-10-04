package dev.mikoto2000.rei.goal;

import java.util.UUID;
import org.springframework.stereotype.Component;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.project.ProjectService;
import picocli.CommandLine.*;

@Component
@Command(name="goal",description="Persistent file goals with independent verification and bounded Chat planning")
public class GoalCommand implements java.util.concurrent.Callable<Integer> {
  private final GoalRepository goals;
  private final GoalLoopService loop;
  private final ProjectService projects;
  @Spec picocli.CommandLine.Model.CommandSpec spec;
  @Parameters(index="0",arity="0..1",defaultValue="list",paramLabel="list|create|show|run|verify|cancel|history") String action;
  @Parameters(index="1",arity="0..1",paramLabel="goalId|objective") String value;
  @Option(names="--file",description="Project-relative completion file") String file;
  @Option(names="--sha256",description="Exact expected file SHA-256") String digest;
  @Option(names="--max-runs",defaultValue="3") int maxRuns;
  @Option(names="--max-llm-calls",defaultValue="20") int maxCalls;
  private java.io.PrintWriter output;
  public GoalCommand(){this(null,null,null);}
  @org.springframework.beans.factory.annotation.Autowired
  public GoalCommand(GoalRepository goals,GoalLoopService loop,ProjectService projects){this.goals=goals;this.loop=loop;this.projects=projects;}
  public void setShellOutput(java.io.PrintWriter output){this.output=output;}
  @Override public Integer call() {
    var writer=output==null?spec.commandLine().getOut():output;
    try {
      var project=projects.currentContext();Object result=switch(action) {
        case "list" -> goals.list(project.id());
        case "show" -> goals.get(project.id(),requiredValue());
        case "history" -> goals.history(project.id(),requiredValue())+"\nAttempts: "+goals.attempts(project.id(),requiredValue());
        case "run" -> loop.run(project.id(),requiredValue());
        case "verify" -> loop.verify(project.id(),requiredValue());
        case "cancel" -> loop.cancel(project.id(),requiredValue());
        case "create" -> {
          String session=projects.currentSessionId();
          if(session==null||session.isBlank())throw new IllegalArgumentException("Create or select a Session before creating a Goal");
          yield loop.create(new AgentRunContext(UUID.randomUUID().toString(),session,project.root(),project.id()),requiredValue(),file,digest,maxRuns,maxCalls);
        }
        default -> throw new IllegalArgumentException("Use /goal list|create|show|run|verify|cancel|history");
      };
      writer.println(dev.mikoto2000.rei.event.CredentialRedactor.redact(String.valueOf(result)));return 0;
    } catch(RuntimeException error){writer.println("[error] "+dev.mikoto2000.rei.event.CredentialRedactor.redact(error.getMessage()));return 2;}
    finally {writer.flush();}
  }
  private String requiredValue(){if(value==null||value.isBlank())throw new IllegalArgumentException("Goal ID or objective is required");return value;}
}
