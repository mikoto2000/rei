package dev.mikoto2000.rei.checkpoint;
import java.util.concurrent.Callable;
import org.springframework.stereotype.Component;
import picocli.CommandLine.*;
import picocli.CommandLine.Model.CommandSpec;
import dev.mikoto2000.rei.core.project.ProjectService;

@Component
@Command(name="checkpoint",description="現在のタスクを保存")
public class CheckpointCommand implements Callable<Integer> {
  private final PersistentCheckpointService service;private final ProjectService projects;
  @Spec CommandSpec spec;private java.io.PrintWriter output;
  public CheckpointCommand(){this(null,null);}
  @org.springframework.beans.factory.annotation.Autowired
  public CheckpointCommand(PersistentCheckpointService service,ProjectService projects){this.service=service;this.projects=projects;}
  public void setShellOutput(java.io.PrintWriter output){this.output=output;}
  public Integer call(){var writer=output==null?spec.commandLine().getOut():output;try {
    var state=service.saveCurrent(projects.currentContext().id(),projects.currentSessionId());
    writer.println("Checkpoint saved: task="+state.taskId()+" revision="+state.revision());return 0;
  }catch(RuntimeException e){writer.println("[error] "+dev.mikoto2000.rei.event.CredentialRedactor.redact(e.getMessage()));return 2;}finally{writer.flush();}}
}
