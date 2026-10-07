package dev.mikoto2000.rei.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.core.project.ProjectContext;
import dev.mikoto2000.rei.core.service.SystemShellService;
import dev.mikoto2000.rei.event.CredentialRedactor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Service;

/** Saved failure -> existing Change Set -> exact human approval -> bounded existing repair cycle. */
@Service
public final class DiagnosedRepairService {
  public record Request(String reportPath,TextChangeSetService.Request change,String testCommand,Integer timeoutSeconds) {}
  public record View(String id,String status,String receiptSha256,TestReportDiagnosisService.Result diagnosis,
      String patchVersion,TextChangeSetService.View changeSet,String testCommand,int timeoutSeconds,
      SelfPatchRepairService.Result verification) {}
  private record Saved(String id,String project,String root,String session,String receiptHash,
      TestReportDiagnosisService.Result diagnosis,String patchVersion,String changeHash,String testCommand,int timeoutSeconds) {}
  private record Stored(Saved saved,String status,SelfPatchRepairService.Result result) {}
  private final JdbcClient db;private final TransactionTemplate transaction;private final TextChangeSetService changes;
  private final Clock clock;private final boolean enabled;private final SelfPatchReviewService.Capture capture;
  private final SelfPatchRepairService.Cycle cycle;private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
  @org.springframework.beans.factory.annotation.Autowired
  public DiagnosedRepairService(@org.springframework.beans.factory.annotation.Qualifier("memoryConsolidationDataSource") DataSource source,
      TextChangeSetService changes,Clock clock,@org.springframework.beans.factory.annotation.Value("${rei.repair.diagnosed-enabled:false}") boolean enabled,SystemShellService shell){
    this(source,changes,clock,enabled,new GitPatchInspector(new dev.mikoto2000.rei.externalagent.ExternalAgentProcessRunner())::capture,new SelfPatchReviewService(shell)::verify);
  }
  public DiagnosedRepairService(DataSource source,TextChangeSetService changes,Clock clock,boolean enabled,
      SelfPatchReviewService.Capture capture,SelfPatchRepairService.Cycle cycle){
    this.changes=changes;this.clock=clock;this.enabled=enabled;this.capture=capture;this.cycle=cycle;
    db=JdbcClient.create(source);transaction=new TransactionTemplate(new DataSourceTransactionManager(source));
    db.sql("CREATE TABLE IF NOT EXISTS diagnosed_repairs(id TEXT PRIMARY KEY,project TEXT NOT NULL,root TEXT NOT NULL,session TEXT NOT NULL,payload TEXT NOT NULL,status TEXT NOT NULL,result TEXT)").update();
  }
  public View propose(AgentRunContext owner,Request request)throws IOException {
    requireEnabled();requireExclusive(owner);Path root=owner(owner);validate(request);long deadline=deadline();
    var diagnosis=new TestReportDiagnosisService().read(root,request.reportPath());requireFailure(diagnosis);
    var snapshot=capture.capture(root,deadline);RunCancellation.propagate(null);
    if(!snapshot.complete()||snapshot.changedFiles().isEmpty()||snapshot.changedFiles().size()>32)throw new IllegalStateException("Complete patch of 1 to 32 files required");
    if(!snapshot.changedFiles().contains(request.change().path().replace('\\','/')))throw new IllegalArgumentException("Repair must select a changed patch file");
    final int seconds=request.timeoutSeconds()==null?30:request.timeoutSeconds();
    var saved=transaction.execute(status->{
      db.sql("UPDATE diagnosed_repairs SET status=status WHERE id=''").update();
      if(db.sql("SELECT count(*) FROM diagnosed_repairs WHERE project=?").param(owner.projectId()).query(Integer.class).single()>=128)throw new IllegalStateException("Diagnosed repair history capacity reached (128)");
      try {
        var proposal=changes.propose(project(owner,root),request.change());
        changes.protectDiagnosed(project(owner,root),proposal.id());
        String hash=hash(proposal.id()+"\u0000"+owner.projectId()+"\u0000"+root+"\u0000"+owner.conversationId()+"\u0000"+diagnosis.sha256()+"\u0000"+snapshot.version()+"\u0000"+proposal.proposalSha256()+"\u0000"+request.testCommand()+"\u0000"+seconds);
        var record=new Saved(proposal.id(),owner.projectId(),root.toString(),owner.conversationId(),hash,diagnosis,snapshot.version(),proposal.proposalSha256(),request.testCommand(),seconds);
        db.sql("INSERT INTO diagnosed_repairs VALUES(?,?,?,?,?,'PROPOSED',NULL)").params(record.id(),record.project(),record.root(),record.session(),encode(record)).update();return record;
      }catch(IOException error){throw new java.io.UncheckedIOException(error);}
    });
    return view(owner,new Stored(saved,"PROPOSED",null));
  }
  public View inspect(AgentRunContext owner,String id)throws IOException {return view(owner,owned(owner,id));}
  public View apply(AgentRunContext owner,String id,String receiptHash,String actualUserRequest,TextChangeSetService.Writer writer)throws IOException {
    requireEnabled();requireExclusive(owner);Path root=owner(owner);var stored=owned(owner,id);var saved=stored.saved();
    if(!Objects.equals(saved.receiptHash(),receiptHash)||!("/repair apply "+id+" "+receiptHash).equals(actualUserRequest==null?null:actualUserRequest.strip()))throw new IllegalArgumentException("Exact human /repair apply ID receiptHash request required");
    if(stored.status().equals("VERIFIED_CHECKS"))return view(owner,stored);
    if(!stored.status().equals("PROPOSED"))throw new IllegalStateException("Repair is claimed or uncertain; inspect without replay");
    long deadline=deadline();var current=new TestReportDiagnosisService().read(root,saved.diagnosis().path());
    if(!current.sha256().equals(saved.diagnosis().sha256()))throw new IllegalStateException("Failure report changed; propose again after diagnosis");
    requireFailure(current);
    var snapshot=capture.capture(root,deadline);if(!snapshot.complete()||!snapshot.version().equals(saved.patchVersion()))throw new IllegalStateException("Patch changed since diagnosis; propose again");
    var proposal=changes.inspect(project(owner,root),id);
    if(!proposal.status().equals("PROPOSED")||!proposal.proposalSha256().equals(saved.changeHash())||!Objects.equals(proposal.baselineSha256(),proposal.currentSha256()))throw new IllegalStateException("Repair Change Set stale, claimed or changed");
    RunCancellation.propagate(null);
    if(db.sql("UPDATE diagnosed_repairs SET status='STARTED' WHERE id=? AND project=? AND status='PROPOSED'").params(id,owner.projectId()).update()!=1)throw new IllegalStateException("Repair already claimed");
    try {
      SelfPatchRepairService.Cycle bounded=(path,request,budget)->{
        var observed=capture.capture(path,budget);
        if(!observed.complete()||observed.changedFiles().size()>32)throw new IOException("Repair patch exceeds 32 files or is incomplete");
        return cycle.verify(path,request,Math.min(deadline,budget));
      };
      var verifier=new SelfPatchRepairService(bounded,capture,(path,repair,budget)->{
        SelfPatchReviewService.remaining(Math.min(deadline,budget),1);
        var reproduced=new TestReportDiagnosisService().read(path,saved.diagnosis().path());requireFailure(reproduced);
        if(!failureIdentities(reproduced).equals(failureIdentities(saved.diagnosis())))throw new IOException("Failure identities changed; diagnose before applying");
        var receipt=changes.applyDiagnosed(project(owner,path),repair.id(),repair.proposalSha256(),writer);
        return new SelfPatchRepairService.Receipt(receipt.id(),receipt.status(),receipt.proposedSha256(),receipt.currentSha256());
      });
      var result=verifier.verify(root,new SelfPatchRepairService.Request(saved.testCommand(),saved.timeoutSeconds(),List.of(new SelfPatchRepairService.Repair(id,saved.changeHash()))),deadline);
      RunCancellation.propagate(null);SelfPatchReviewService.remaining(deadline,1);
      if(db.sql("UPDATE diagnosed_repairs SET status=?,result=? WHERE id=? AND project=? AND status='STARTED'").params(result.status(),encode(result),id,owner.projectId()).update()!=1)throw new IOException("Repair receipt unavailable");
      return view(owner,new Stored(saved,result.status(),result));
    }catch(IOException|RuntimeException error){
      try{db.sql("UPDATE diagnosed_repairs SET status='UNKNOWN' WHERE id=? AND project=? AND status='STARTED'").params(id,owner.projectId()).update();}catch(RuntimeException persistence){error.addSuppressed(persistence);}
      RunCancellation.propagate(error);throw error;
    }
  }
  private Stored owned(AgentRunContext owner,String id)throws IOException {
    Path root=owner(owner);if(id==null||!id.matches("[0-9a-fA-F-]{36}"))throw new IllegalArgumentException("Repair UUID required");
    var stored=db.sql("SELECT payload,status,result FROM diagnosed_repairs WHERE id=? AND project=? AND root=? AND session=?")
        .params(id,owner.projectId(),root.toString(),owner.conversationId()).query((row,n)->new Stored(decode(row.getString("payload"),Saved.class),row.getString("status"),row.getString("result")==null?null:decode(row.getString("result"),SelfPatchRepairService.Result.class))).optional().orElseThrow(()->new IllegalArgumentException("Repair not found in this Project/root/session"));
    if(!id.equals(stored.saved().id())||!owner.projectId().equals(stored.saved().project())||!root.toString().equals(stored.saved().root())||!owner.conversationId().equals(stored.saved().session()))throw new IllegalStateException("Repair ownership receipt invalid");return stored;
  }
  private View view(AgentRunContext owner,Stored stored)throws IOException {var saved=stored.saved();return new View(saved.id(),stored.status(),saved.receiptHash(),saved.diagnosis(),saved.patchVersion(),changes.inspect(project(owner,Path.of(saved.root())),saved.id()),saved.testCommand(),saved.timeoutSeconds(),stored.result());}
  private void requireFailure(TestReportDiagnosisService.Result diagnosis){if(diagnosis.partial()||diagnosis.failedTests().isEmpty()||diagnosis.observed().failures()+diagnosis.observed().errors()==0)throw new IllegalArgumentException("Complete saved failing test evidence required");if(diagnosis.modifiedAt().isBefore(clock.instant().minus(Duration.ofDays(7)))||diagnosis.modifiedAt().isAfter(clock.instant().plusSeconds(60)))throw new IllegalStateException("Failure report is stale or future dated");}
  private static Set<String> failureIdentities(TestReportDiagnosisService.Result diagnosis){var identities=new TreeSet<String>();for(var failure:diagnosis.failedTests())identities.add(failure.test()+"\u0000"+failure.kind());return identities;}
  private static Path owner(AgentRunContext owner)throws IOException {RunCancellation.propagate(null);if(owner==null||owner.projectId()==null||owner.conversationId().startsWith("subagent:"))throw new IllegalArgumentException("Human Project/session owner required");return owner.projectRoot().toRealPath();}
  private static void requireExclusive(AgentRunContext owner){RunCancellation.propagate(null);if(owner==null||owner.mode()!=AgentRunContext.Mode.EXCLUSIVE)throw new IllegalArgumentException("Exclusive human Run required for repair writes");}
  private static ProjectContext project(AgentRunContext owner,Path root){return new ProjectContext(owner.projectId(),"",root);}
  private void requireEnabled(){if(!enabled)throw new IllegalStateException("Enable rei.repair.diagnosed-enabled before diagnosed repair");}
  private static void validate(Request request){if(request==null||request.change()==null||request.testCommand()==null||request.testCommand().isBlank()||request.testCommand().length()>4096||!CredentialRedactor.redact(request.testCommand()).equals(request.testCommand())||request.testCommand().codePoints().anyMatch(Character::isISOControl)||request.timeoutSeconds()!=null&&(request.timeoutSeconds()<1||request.timeoutSeconds()>60))throw new IllegalArgumentException("Bounded explicit repair, nonsecret test command and 1..60 seconds required");}
  private static long deadline(){return System.nanoTime()+Duration.ofSeconds(180).toNanos();}
  private String encode(Object value){try{String encoded=json.writeValueAsString(value);if(encoded.getBytes(StandardCharsets.UTF_8).length>131072)throw new IllegalArgumentException("Repair receipt exceeds 128KiB");return encoded;}catch(IOException invalid){throw new IllegalStateException("Repair receipt unavailable",invalid);}}
  private <T>T decode(String value,Class<T> type){try{if(value.length()>131072)throw new IllegalStateException("Repair receipt exceeds bounds");return json.readValue(value,type);}catch(IOException invalid){throw new IllegalStateException("Repair receipt unavailable",invalid);}}
  private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
}
