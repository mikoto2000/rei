package dev.mikoto2000.rei.core.command;

import org.springframework.stereotype.Component;
import picocli.CommandLine.*;
import dev.mikoto2000.rei.application.session.ShellConversationService;

/** Explicit human instruction enters the existing exclusive conversation queue. No model-supplied approval. */
@Component
@Command(name="repair",description="診断済みの修復を確認し、保存された修復記録のハッシュを指定して明示的に承認します")
public final class RepairCommand implements java.util.concurrent.Callable<Integer> {
  private final ShellConversationService conversations;
  @Parameters(index="0",paramLabel="show|apply") String action;
  @Parameters(index="1",paramLabel="ID") String id;
  @Parameters(index="2",arity="0..1",paramLabel="RECEIPT_SHA256") String hash;
  @Spec picocli.CommandLine.Model.CommandSpec spec;
  public RepairCommand(){this(null);}
  @org.springframework.beans.factory.annotation.Autowired public RepairCommand(ShellConversationService conversations){this.conversations=conversations;}
  @Override public Integer call(){
    if(conversations==null||id==null||!id.matches("[0-9a-fA-F-]{36}")||!(action.equals("show")&&hash==null||action.equals("apply")&&hash!=null&&hash.matches("[a-f0-9]{64}"))){spec.commandLine().getErr().println("Use /repair show UUID or /repair apply UUID receiptSha256");return 2;}
    conversations.submit("/repair "+action+" "+id+(hash==null?"":" "+hash));return 0;
  }
}
