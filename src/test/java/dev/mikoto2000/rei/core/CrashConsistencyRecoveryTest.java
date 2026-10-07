package dev.mikoto2000.rei.core;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.attention.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@org.junit.jupiter.api.Tag("integration")
class CrashConsistencyRecoveryTest {
  @TempDir Path directory;
  @ParameterizedTest @ValueSource(strings={"review","repair","notification","single"})
  void hardKilledJvmLeavesUnknownWithoutReplayAndLiveProcessIsPreserved(String mode)throws Exception{
    Path root=Files.createDirectory(directory.resolve("project")),db=directory.resolve("receipts.db"),ready=directory.resolve("ready");String project=UUID.randomUUID().toString();Files.writeString(root.resolve("A.java"),"old");
    var process=new ProcessBuilder(dev.mikoto2000.rei.testsupport.JavaFixtureCommand.command(directory,Worker.class,mode,root.toString(),db.toString(),ready.toString(),project)).redirectErrorStream(true).redirectOutput(directory.resolve("child.log").toFile()).start();
    try{long deadline=System.nanoTime()+Duration.ofSeconds(20).toNanos();while(!Files.exists(ready)&&process.isAlive()&&System.nanoTime()<deadline)Thread.sleep(20);assertTrue(Files.exists(ready),"Child did not publish its durable claim");String id=Files.readString(ready);var source=new DriverManagerDataSource("jdbc:sqlite:"+db);var owner=new AgentRunContext("run","session",root,project);
      if(mode.equals("review")){var service=review(source,root,null);assertEquals("STARTED",service.inspect(owner,id).status());process.destroyForcibly();assertTrue(process.waitFor(5,java.util.concurrent.TimeUnit.SECONDS));var state=service.inspect(owner,id);assertEquals("UNKNOWN",state.status());assertFalse(state.completed());assertNull(state.sha256());assertThrows(IllegalStateException.class,()->service.review(owner,reviewRequest(),null));}
      else if(mode.equals("repair")){var service=repair(source,root,null);assertEquals("STARTED",service.inspect(owner,id).status());process.destroyForcibly();assertTrue(process.waitFor(5,java.util.concurrent.TimeUnit.SECONDS));var state=service.inspect(owner,id);assertEquals("UNKNOWN",state.status());assertThrows(IllegalStateException.class,()->service.apply(owner,id,state.receiptSha256(),"/repair apply "+id+" "+state.receiptSha256(),(p,o,n)->fail("No crash replay")));}
      else if(mode.equals("single")){var service=new TextChangeSetService(new TextChangeSetRepository(source));var projectContext=new dev.mikoto2000.rei.core.project.ProjectContext(project,"anonymous",root);assertEquals("APPLYING",service.inspect(projectContext,id).status());process.destroyForcibly();assertTrue(process.waitFor(5,java.util.concurrent.TimeUnit.SECONDS));var state=service.inspect(projectContext,id);assertEquals("UNKNOWN",state.status());assertEquals("partial",Files.readString(root.resolve("A.java")));assertThrows(IllegalStateException.class,()->service.apply(projectContext,id,state.proposalSha256(),(p,o,n)->fail("No crash replay")));Files.writeString(root.resolve("A.java"),"old");assertEquals("RECONCILED",service.reconcile(owner,id,state.proposalSha256(),"/document reconcile-single "+id+" "+state.proposalSha256()).status());}
      else{var outbox=new AttentionDeliveryRepository(source,Clock.systemUTC());outbox.recover();assertEquals("SENDING",outbox.find(project,id).orElseThrow().status());var old=outbox.find(project,id).orElseThrow();process.destroyForcibly();assertTrue(process.waitFor(5,java.util.concurrent.TimeUnit.SECONDS));outbox.recover();assertEquals("UNKNOWN",outbox.find(project,id).orElseThrow().status());outbox.finish(old,"SENT","late_outcome");assertEquals("UNKNOWN",outbox.find(project,id).orElseThrow().status());assertTrue(outbox.pending().isEmpty());}
      assertEquals("old",Files.readString(root.resolve("A.java")));
    }finally{if(process.isAlive())process.destroyForcibly();assertTrue(process.waitFor(5,java.util.concurrent.TimeUnit.SECONDS));}
  }
  static SelfPatchReviewService.Snapshot snapshot(){return new SelfPatchReviewService.Snapshot("a".repeat(64),List.of("A.java"),List.of(),true,List.of());}
  static SemanticPatchReviewService.Request reviewRequest(){return new SemanticPatchReviewService.Request("fixture",10,List.of(new SemanticPatchReviewService.Requirement("R1","anonymous requirement",List.of("A.java"),List.of("ATest#value"))),List.of("A.java"),List.of("target/TEST.xml"),false);}
  static SemanticPatchReviewService review(DriverManagerDataSource source,Path root,Path ready){return new SemanticPatchReviewService(source,Clock.systemUTC(),false,(r,d)->snapshot(),(r,q,d)->{hold(source,"patch_requirement_reviews",ready);throw new java.io.IOException("fixture stopped");},(r,s,d)->{throw new AssertionError("No replay");},(i,r,d)->{throw new AssertionError("No model calls");});}
  static DiagnosedRepairService repair(DriverManagerDataSource source,Path root,Path ready){return new DiagnosedRepairService(source,new TextChangeSetService(new TextChangeSetRepository(source)),Clock.systemUTC(),true,(r,d)->snapshot(),(r,q,d)->{hold(source,"diagnosed_repairs",ready);throw new java.io.IOException("fixture stopped");});}
  static void hold(DriverManagerDataSource source,String table,Path ready)throws java.io.IOException{if(ready==null)throw new AssertionError("No replay");String id=org.springframework.jdbc.core.simple.JdbcClient.create(source).sql("SELECT id FROM "+table+" WHERE status='STARTED'").query(String.class).single();publish(ready,id);try{Thread.sleep(60000);}catch(InterruptedException stopped){Thread.currentThread().interrupt();throw new java.io.IOException("fixture stopped",stopped);}}
  static void publish(Path ready,String id)throws java.io.IOException{var staging=ready.resolveSibling("ready.tmp");Files.writeString(staging,id);Files.move(staging,ready,StandardCopyOption.ATOMIC_MOVE);}
  public static final class Worker {
    public static void main(String[] arguments)throws Exception{String mode=arguments[0];Path root=Path.of(arguments[1]),ready=Path.of(arguments[3]);var source=new DriverManagerDataSource("jdbc:sqlite:"+arguments[2]);var owner=new AgentRunContext("run","session",root,arguments[4]);
      if(mode.equals("review"))review(source,root,ready).review(owner,reviewRequest(),null);
      else if(mode.equals("repair")){Files.createDirectories(root.resolve("target"));Files.writeString(root.resolve("target/TEST.xml"),"<testsuite tests=\"1\" failures=\"1\" errors=\"0\" skipped=\"0\"><testcase classname=\"ATest\" name=\"value\"><failure message=\"anonymous failure\">assertion</failure></testcase></testsuite>");var service=repair(source,root,ready);var proposal=service.propose(owner,new DiagnosedRepairService.Request("target/TEST.xml",new TextChangeSetService.Request("A.java","old","new"),"fixture",10));service.apply(owner,proposal.id(),proposal.receiptSha256(),"/repair apply "+proposal.id()+" "+proposal.receiptSha256(),(p,o,n)->{throw new AssertionError("No source write before verification");});}
      else if(mode.equals("single")){var service=new TextChangeSetService(new TextChangeSetRepository(source));var project=new dev.mikoto2000.rei.core.project.ProjectContext(owner.projectId(),"anonymous",root);var proposal=service.propose(project,new TextChangeSetService.Request("A.java","old","new"));service.apply(project,proposal.id(),proposal.proposalSha256(),(p,o,n)->{Files.writeString(p,"partial");publish(ready,proposal.id());try{Thread.sleep(60000);}catch(InterruptedException stopped){Thread.currentThread().interrupt();throw new java.io.IOException("fixture stopped",stopped);}});}
      else{var outbox=new AttentionDeliveryRepository(source,Clock.systemUTC());String id=UUID.randomUUID().toString();var pending=outbox.enqueue(new AttentionRepository.Item(id,owner.projectId(),"session","run","RUN_COMPLETED","","","OPEN",Instant.now()),"anonymous-destination","MANUAL");try(var lease=outbox.claimActive(pending,0)){if(lease==null)throw new AssertionError("Missing claim");publish(ready,id);Thread.sleep(60000);}}
    }
  }
}
