package dev.mikoto2000.rei.core.dependency;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import dev.mikoto2000.rei.core.project.ProjectService;
import dev.mikoto2000.rei.core.policy.ToolPermissionGuard;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.event.CredentialRedactor;
import picocli.CommandLine.*;

@Component
@Command(name="dependency",description="保存された依存関係の監視を確認・キャンセル・回答します")
public class DependencyCommand implements java.util.concurrent.Callable<Integer> {
  private final PersistentDependencyRepository repo;private final DependencyObservationService service;private final ProjectService projects;private final ToolPermissionGuard guard;
  @Spec picocli.CommandLine.Model.CommandSpec spec;
  @Parameters(index="0",arity="0..1",defaultValue="list") String action;
  @Parameters(index="1",arity="0..1") String id;
  @Option(names="--value",description="Human answer, never supplied through an LLM tool") String value;
  private java.io.PrintWriter output;
  public DependencyCommand(){this(null,null,null,null);}
  @Autowired public DependencyCommand(PersistentDependencyRepository repo,DependencyObservationService service,ProjectService projects,ToolPermissionGuard guard){this.repo=repo;this.service=service;this.projects=projects;this.guard=guard;}
  public void setShellOutput(java.io.PrintWriter output){this.output=output;}
  @Override public Integer call() {
    var writer=output==null?spec.commandLine().getOut():output;
    try {
      var project=projects.currentContext();Object result;
      if(action.equals("list"))result=repo.list(project.id());
      else {
        if(id==null||id.isBlank())throw new IllegalArgumentException("Dependency ID required");
        var entry=repo.get(project.id(),id);var owner=new AgentRunContext(entry.id(),entry.sessionId(),project.root(),project.id());
        result=switch(action) {
          case "show"->entry;case "history"->repo.history(project.id(),id);
          case "check"->{boolean network=entry.spec().network();guard.check(network?"checkHttpDependency":"checkDependency","{\"dependencyId\":\""+entry.id()+"\"}",owner);yield service.inspect(project.id(),id,network);}
          case "cancel"->{yield repo.cancel(project.id(),id);}
          case "answer"->{repo.answer(project.id(),id,value);yield service.inspect(project.id(),id,false);}
          default->throw new IllegalArgumentException("Use /dependency list|show|history|check|cancel|answer ID [--value answer]");
        };
      }
      service.flushFacts();writer.println(CredentialRedactor.redact(String.valueOf(result)));return 0;
    }catch(RuntimeException error){writer.println("[error] "+CredentialRedactor.redact(error.getMessage()));return 2;}
    finally {writer.flush();}
  }
}
