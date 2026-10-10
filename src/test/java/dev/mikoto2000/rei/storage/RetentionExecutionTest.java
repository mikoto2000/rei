package dev.mikoto2000.rei.storage;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

@Tag("integration")
class RetentionExecutionTest {
  @TempDir Path root;
  static final class MutableClock extends Clock {
    Instant time=Instant.parse("2026-10-10T00:00:00Z");
    public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return time;}
  }
  final MutableClock clock=new MutableClock();
  StorageObjectRegistry registry;RetentionPlanner planner;RetentionExecutor executor;
  @BeforeEach void prepare()throws Exception {
    try(var gate=new StorageMigrationCoordinator(root)){gate.prepare();}
    registry=new StorageObjectRegistry(root,clock);planner=new RetentionPlanner(registry);executor=new RetentionExecutor(registry,planner);
  }
  StorageObjectRegistry.StoredObject archive()throws Exception {
    Path file=root.resolve("logs/activity-archives/"+UUID.randomUUID()+".jsonl");Files.createDirectories(file.getParent());Files.writeString(file,"{\"diagnostic\":true}\n");
    return registry.registerProducedFile(file,"ACTIVITY_RAW",null,null,clock.instant().minus(Duration.ofDays(100)),true,List.of());
  }
  RetentionPlanner.Plan plan(){return planner.plan("activity-raw",null,10,1024*1024);}
  @Test void approvalIsRequiredAndExactBodyCanBeQuarantinedAndRestored()throws Exception {
    var body=archive();var plan=plan();Path original=root.resolve(body.relativePath());String hash=StorageBackup.hash(original);
    assertThatThrownBy(()->executor.apply(plan.id())).isInstanceOf(IllegalStateException.class);assertThat(original).exists();
    planner.approve(plan.id());var execution=executor.apply(plan.id());assertThat(original).doesNotExist();
    assertThat(execution.status()).isEqualTo("QUARANTINED");assertThat(executor.apply(plan.id()).id()).isEqualTo(execution.id());
    assertThatThrownBy(()->registry.addReference(body.id(),"CHECKPOINT","new-owner")).isInstanceOf(IllegalStateException.class);
    executor.restore(execution.id());assertThat(StorageBackup.hash(original)).isEqualTo(hash);assertThat(registry.protectionReason(body)).isEmpty();
    registry.addReference(body.id(),"CHECKPOINT","restored-owner");assertThat(plan().candidates()).isEmpty();
  }
  @Test void newPinAfterApprovalPreventsAnyMove()throws Exception {
    var body=archive();var plan=plan();planner.approve(plan.id());registry.pin(body.id(),true);
    assertThatThrownBy(()->executor.apply(plan.id())).isInstanceOf(IllegalStateException.class);assertThat(root.resolve(body.relativePath())).exists();
  }
  @Test void purgeNeedsSeparateApprovalAndGraceAndPreservesHeldBodies()throws Exception {
    var body=archive();var plan=plan();planner.approve(plan.id());var execution=executor.apply(plan.id());
    assertThatThrownBy(()->executor.approvePurge(execution.id())).isInstanceOf(IllegalStateException.class);
    clock.time=clock.time.plus(Duration.ofDays(8));assertThatThrownBy(()->executor.purge(execution.id())).isInstanceOf(IllegalStateException.class);
    executor.approvePurge(execution.id());registry.legalHold(body.id(),true);assertThatThrownBy(()->executor.purge(execution.id())).isInstanceOf(IllegalStateException.class);
    registry.legalHold(body.id(),false);executor.approvePurge(execution.id());executor.purge(execution.id());
    assertThat(executor.get(execution.id()).status()).isEqualTo("DELETED");assertThatThrownBy(()->executor.restore(execution.id())).isInstanceOf(IllegalStateException.class);
    assertThat(planner.status(null).objects()).allSatisfy(usage->assertThat(usage.recordedBodyBytes()).isZero());
  }
  @Test void restorationNeverOverwritesAnExistingOriginal()throws Exception {
    var body=archive();var plan=plan();planner.approve(plan.id());var execution=executor.apply(plan.id());Path original=root.resolve(body.relativePath());Files.writeString(original,"new content");
    assertThatThrownBy(()->executor.restore(execution.id())).isInstanceOf(IllegalStateException.class);assertThat(Files.readString(original)).isEqualTo("new content");
  }
  @Test void changedBodyAfterApprovalRemainsAtOriginalPath()throws Exception {
    var body=archive();var plan=plan();planner.approve(plan.id());Path original=root.resolve(body.relativePath());Files.writeString(original,"changed");
    assertThatThrownBy(()->executor.apply(plan.id())).isInstanceOf(IllegalStateException.class);assertThat(Files.readString(original)).isEqualTo("changed");
  }
  @Test void policyChangeStopsAnInterruptedApplyAndLeavesRestorationAvailable()throws Exception {
    var body=archive();var plan=plan();planner.approve(plan.id());var stopped=new RetentionExecutor(registry,planner,(stage,id)->{if(stage.equals("DELETING"))throw new IllegalStateException("fixture interruption");});
    assertThatThrownBy(()->stopped.apply(plan.id())).isInstanceOf(IllegalStateException.class);planner.updatePolicy("activity-raw",Duration.ofDays(365),null,null);
    assertThatThrownBy(()->executor.apply(plan.id())).isInstanceOf(IllegalStateException.class);assertThat(root.resolve(body.relativePath())).exists();
    executor.restore(executor.history().getFirst().id());assertThat(root.resolve(body.relativePath())).exists();
  }
  @Test void changedPolicyInvalidatesExistingPurgeApproval()throws Exception {
    var body=archive();var plan=plan();planner.approve(plan.id());var execution=executor.apply(plan.id());clock.time=clock.time.plus(Duration.ofDays(8));executor.approvePurge(execution.id());
    planner.updatePolicy("activity-raw",Duration.ofDays(365),null,null);assertThatThrownBy(()->executor.purge(execution.id())).isInstanceOf(IllegalStateException.class);
    assertThat(executor.get(execution.id()).status()).isEqualTo("QUARANTINED");executor.restore(execution.id());assertThat(root.resolve(body.relativePath())).exists();
  }
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings={"apply","restore","purge"})
  void processKillAfterFileMutationCanResumeFromDurableJournal(String action)throws Exception {
    var body=archive();var plan=plan();planner.approve(plan.id());String target=plan.id(),stage="MOVED",execution=null;
    if(!action.equals("apply")){execution=executor.apply(plan.id()).id();target=execution;stage=action.equals("restore")?"RESTORED_FILE":"PURGED_FILE";}
    if(action.equals("purge")){clock.time=clock.time.plus(Duration.ofDays(8));executor.approvePurge(execution);}
    Path arguments=root.resolve("crash.args");String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
    Files.writeString(arguments,"-cp\n"+arg(classpath)+"\n"+RetentionCrashWorker.class.getName()+"\n"+arg(root.toString())+"\n"+action+"\n"+target+"\n"+stage+"\n"+clock.instant()+"\n");
    Path output=root.resolve("crash.log");var child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString(),"@"+arguments).redirectErrorStream(true).redirectOutput(output.toFile()).start();
    try {
      long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(60);while(child.isAlive()&&System.nanoTime()<deadline&&!Files.readString(output).contains("PAUSED"))Thread.sleep(50);
      assertThat(Files.readString(output)).contains("PAUSED");assertThatThrownBy(()->new StorageMigrationCoordinator(root)).isInstanceOf(java.io.IOException.class);
    }finally{child.destroyForcibly();assertThat(child.waitFor(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();}
    try(var gate=new StorageMigrationCoordinator(root)){gate.prepare();}
    executor=new RetentionExecutor(registry,planner);
    if(action.equals("apply")){var result=executor.apply(plan.id());assertThat(result.status()).isEqualTo("QUARANTINED");executor.restore(result.id());}
    if(action.equals("restore"))assertThat(executor.restore(target).status()).isEqualTo("RESTORED");
    if(action.equals("purge")){
      registry.legalHold(body.id(),true);String executionId=target;assertThatThrownBy(()->executor.purge(executionId)).isInstanceOf(IllegalStateException.class);
      registry.legalHold(body.id(),false);assertThatThrownBy(()->executor.purge(executionId)).isInstanceOf(IllegalStateException.class);
      executor.approvePurge(target);assertThat(executor.purge(target).status()).isEqualTo("DELETED");
    }
    assertThat(Files.exists(root.resolve(body.relativePath()))).isEqualTo(!action.equals("purge"));
  }
  private static String arg(String value){return "\""+value.replace("\\","\\\\").replace("\"","\\\"")+"\"";}
}
