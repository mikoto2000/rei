package dev.mikoto2000.rei.core.command;
import org.springframework.stereotype.Component;
import picocli.CommandLine.*;
import dev.mikoto2000.rei.application.session.ShellConversationService;
/** Human recovery stays inside the existing captured exclusive conversation boundary. */
@Component
@Command(name="document",description="Inspect multi-file proposals or explicitly recover owned text/staging")
public final class DocumentCommand implements java.util.concurrent.Callable<Integer> {
  private final ShellConversationService conversations;
  @Parameters(index="0",paramLabel="show|rollback|clean|reconcile-single") String action;
  @Parameters(index="1",paramLabel="ID") String id;
  @Parameters(index="2",arity="0..1",paramLabel="PROPOSAL_SHA256") String hash;
  @Spec picocli.CommandLine.Model.CommandSpec spec;
  public DocumentCommand(){this(null);}
  @org.springframework.beans.factory.annotation.Autowired public DocumentCommand(ShellConversationService conversations){this.conversations=conversations;}
  @Override public Integer call(){if(conversations==null||id==null||!id.matches("[a-f0-9-]{36}")||!(action.equals("show")&&hash==null||java.util.Set.of("rollback","clean","reconcile-single").contains(action)&&hash!=null&&hash.matches("[a-f0-9]{64}"))){spec.commandLine().getErr().println("Use /document show UUID or /document rollback|clean|reconcile-single UUID proposalSha256");return 2;}conversations.submit("/document "+action+" "+id+(hash==null?"":" "+hash));return 0;}
}
