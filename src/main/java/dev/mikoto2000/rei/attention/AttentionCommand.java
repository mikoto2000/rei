package dev.mikoto2000.rei.attention;

import org.springframework.stereotype.Component;
import dev.mikoto2000.rei.core.project.ProjectService;
import picocli.CommandLine.*;

@Component
@Command(name="attention",description="保存された注意事項を確認・確認済みにします")
public class AttentionCommand implements java.util.concurrent.Callable<Integer> {
  private final AttentionRepository repository;
  private final ProjectService projects;
  @Spec picocli.CommandLine.Model.CommandSpec spec;
  @Parameters(index="0",arity="0..1",defaultValue="list",paramLabel="list|show|ack|delivery|deliver|retry-delivery") String action;
  @Parameters(index="1",arity="0..1",paramLabel="attentionId") String id;
  private java.io.PrintWriter output;
  private AttentionDeliveryService delivery;
  @Option(names="--acknowledge-duplicate-risk",description="A prior delivery may have succeeded; explicitly accept duplicate notification risk") boolean duplicateRisk;
  @org.springframework.beans.factory.annotation.Autowired(required=false)
  public void setDelivery(AttentionDeliveryService delivery){this.delivery=delivery;}
  public AttentionCommand(){this(null,null);}
  @org.springframework.beans.factory.annotation.Autowired
  public AttentionCommand(AttentionRepository repository,ProjectService projects){this.repository=repository;this.projects=projects;}
  public void setShellOutput(java.io.PrintWriter output){this.output=output;}
  @Override public Integer call() {
    var writer=output==null?spec.commandLine().getOut():output;
    try {
      String project=projects.currentContext().id();
      Object result=switch(action) {
        case "list" -> repository.list(project);
        case "show" -> repository.get(project,requiredId());
        case "ack" -> {repository.acknowledge(project,requiredId());yield "Acknowledged. This does not approve, resume or execute the task.";}
        case "delivery" -> requireDelivery().status(project,requiredId());
        case "deliver" -> requireDelivery().request(project,requiredId());
        case "retry-delivery" -> requireDelivery().retry(project,requiredId(),duplicateRisk);
        default -> throw new IllegalArgumentException("Use /attention list|show|ack|delivery|deliver|retry-delivery [attentionId]");
      };
      writer.println(dev.mikoto2000.rei.event.CredentialRedactor.redact(String.valueOf(result)));return 0;
    } catch(RuntimeException error){writer.println("[error] "+dev.mikoto2000.rei.event.CredentialRedactor.redact(error.getMessage()));return 2;}
    finally {writer.flush();}
  }
  private String requiredId(){if(id==null||id.isBlank())throw new IllegalArgumentException("attentionId is required");return id;}
  private AttentionDeliveryService requireDelivery(){if(delivery==null)throw new IllegalArgumentException("Attention delivery is unavailable");return delivery;}
}
