package dev.mikoto2000.rei.reflection;

import org.springframework.stereotype.Component;
import dev.mikoto2000.rei.core.project.ProjectService;
import picocli.CommandLine.*;

@Component
@Command(name="reflection",description="Inspect saved Goal evidence and conservative review suggestions")
public class ReflectionCommand implements java.util.concurrent.Callable<Integer> {
  private final GoalReflectionRepository repository;
  private final GoalReflectionService service;
  private final ProjectService projects;
  @Spec picocli.CommandLine.Model.CommandSpec spec;
  @Parameters(index="0",arity="0..1",defaultValue="list",paramLabel="list|show|collect") String action;
  @Parameters(index="1",arity="0..1",paramLabel="reflectionId|goalId") String id;
  private java.io.PrintWriter output;
  public ReflectionCommand(){this(null,null,null);}
  @org.springframework.beans.factory.annotation.Autowired
  public ReflectionCommand(GoalReflectionRepository repository,GoalReflectionService service,ProjectService projects){this.repository=repository;this.service=service;this.projects=projects;}
  public void setShellOutput(java.io.PrintWriter output){this.output=output;}
  @Override public Integer call() {
    var writer=output==null?spec.commandLine().getOut():output;
    try {
      String project=projects.currentContext().id();Object result=switch(action) {
        case "list" -> repository.list(project);
        case "show" -> repository.get(project,requiredId());
        case "collect" -> service.collect(project,requiredId());
        default -> throw new IllegalArgumentException("Use /reflection list|show|collect [id]");
      };
      writer.println(dev.mikoto2000.rei.event.CredentialRedactor.redact(String.valueOf(result)));return 0;
    } catch(RuntimeException error){writer.println("[error] "+dev.mikoto2000.rei.event.CredentialRedactor.redact(error.getMessage()));return 2;}
    finally {writer.flush();}
  }
  private String requiredId(){if(id==null||id.isBlank())throw new IllegalArgumentException("Reflection ID or Goal ID is required");return id;}
}
