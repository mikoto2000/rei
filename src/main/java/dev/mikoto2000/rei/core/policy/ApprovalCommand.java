package dev.mikoto2000.rei.core.policy;

import org.springframework.stereotype.Component;
import dev.mikoto2000.rei.core.project.ProjectService;
import picocli.CommandLine.*;

@Component
@Command(name="approval",description="ツールの一回限りの実行承認を確認・判断します")
public class ApprovalCommand implements java.util.concurrent.Callable<Integer> {
  private final ToolApprovalRepository repository;
  private final ProjectService projects;
  @Spec picocli.CommandLine.Model.CommandSpec spec;
  @Parameters(index="0",arity="0..1",defaultValue="list",paramLabel="list|show|approve|deny") String action;
  @Parameters(index="1",arity="0..1",paramLabel="approvalId") String id;
  private java.io.PrintWriter output;
  public ApprovalCommand(){this(null,null);}
  @org.springframework.beans.factory.annotation.Autowired
  public ApprovalCommand(ToolApprovalRepository repository,ProjectService projects){this.repository=repository;this.projects=projects;}
  public void setShellOutput(java.io.PrintWriter output){this.output=output;}
  @Override public Integer call() {
    var writer=output==null?spec.commandLine().getOut():output;
    try {
      String project=projects.currentContext().id();
      Object result=switch(action) {
        case "list" -> repository.list(project);
        case "show" -> repository.get(project,requiredId());
        case "approve" -> repository.decide(project,requiredId(),true);
        case "deny" -> repository.decide(project,requiredId(),false);
        default -> throw new IllegalArgumentException("Use /approval list|show|approve|deny [approvalId]");
      };
      writer.println(result);return 0;
    } catch(RuntimeException error) {writer.println("[error] "+dev.mikoto2000.rei.event.CredentialRedactor.redact(error.getMessage()));return 2;}
    finally {writer.flush();}
  }
  private String requiredId(){if(id==null||id.isBlank())throw new IllegalArgumentException("approvalId is required");return id;}
}
