package dev.mikoto2000.rei.application.task;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.application.run.*;
import dev.mikoto2000.rei.checkpoint.*;
import dev.mikoto2000.rei.core.dependency.*;
import dev.mikoto2000.rei.goal.GoalRepository;
import dev.mikoto2000.rei.temporal.PersistentAgentScheduler;
import static org.assertj.core.api.Assertions.*;

@Tag("integration")
class TaskProjectionSourcesTest {
  @Test void foreignOrUnknownGoalExecutionCannotAdvertiseCancellationOrSuspend() {
    var projects=new ProjectRegistry(root.resolve("projects.json"));var project=projects.resolve(root);
    var source=new SQLiteDataSource();source.setUrl("jdbc:sqlite:"+root.resolve("foreign.db"));
    var goals=new GoalRepository(source,Clock.systemUTC());var owner=new AgentRunContext("parent","session",root,project.id());
    var goal=goals.create(owner,"objective","output.txt","a".repeat(64),2,2);
    var claim=goals.claim(project.id(),goal.id());String id=goals.beginAttempt(claim);
    var missing=new TaskManagerService(projects,new RunRegistry(Clock.systemUTC()),null,goals,null,null);
    assertThat(missing.get(project.id(),"session","goal:"+goal.id()).status()).isEqualTo("UNKNOWN");
    assertThat(missing.get(project.id(),"session","goal:"+goal.id()).cancelSupported()).isFalse();
    var runs=new RunRegistry(Clock.systemUTC(),source);runs.register(new AgentRunContext(id,"session",root,project.id()));runs.transition(id,RunStatus.RUNNING,null);
    var observer=new TaskManagerService(projects,new RunRegistry(Clock.systemUTC(),source),null,goals,null,null);
    var task=observer.get(project.id(),"session","goal:"+goal.id());
    assertThat(task.status()).isEqualTo("RUNNING");assertThat(task.cancelSupported()).isFalse();assertThat(task.suspendSupported()).isFalse();
    runs.close();assertThat(observer.get(project.id(),"session","goal:"+goal.id()).status()).isEqualTo("UNKNOWN");
  }
  @Test void goalWaitingAndFailureResumeKeepTheExistingBudgetLimits() {
    var projects=new ProjectRegistry(root.resolve("projects.json"));var project=projects.resolve(root);
    var source=new SQLiteDataSource();source.setUrl("jdbc:sqlite:"+root.resolve("goals.db"));
    var goals=new GoalRepository(source,Clock.systemUTC());var runs=new RunRegistry(Clock.systemUTC());
    var tasks=new TaskManagerService(projects,runs,null,goals,null,null);
    var owner=new AgentRunContext("parent","session",root,project.id());
    for(String status:List.of("WAITING_APPROVAL","FAILED","PAUSED")) {
      var goal=goals.create(owner,"objective","output.txt","a".repeat(64),2,2);
      var claim=goals.claim(project.id(),goal.id());goals.beginAttempt(claim);goals.stop(claim,status,"reason");
      var task=tasks.get(project.id(),"session","goal:"+goal.id());
      assertThat(task.status()).isEqualTo(status.equals("WAITING_APPROVAL")?"WAITING":status.equals("PAUSED")?"SUSPENDED":status);
      assertThat(task.resumeSupported()).isTrue();
      claim=goals.claim(project.id(),goal.id());goals.beginAttempt(claim);goals.stop(claim,"FAILED","limit");
      assertThat(tasks.get(project.id(),"session","goal:"+goal.id()).resumeSupported()).isFalse();
    }
  }
  @TempDir Path root;
  @Test void checkpointKeepsOriginalTaskIdentityAfterResumeAndProjectsProgressWithoutResettingState() {
    var projects=new ProjectRegistry(root.resolve("projects.json"));var project=projects.resolve(root);
    var source=new SQLiteDataSource();source.setUrl("jdbc:sqlite:"+root.resolve("state.db"));
    var checkpoints=new PersistentCheckpointRepository(source,new CheckpointProperties());
    var original=PersistentCheckpoint.initial("checkpoint",project.id(),"session","original",root,"work");
    var fields=checkpoints.fields(original);fields.put("plan",List.of(Map.of("id","one","status","DONE"),Map.of("id","two","status","TODO")));
    var first=checkpoints.save(checkpoints.fields(fields),0,"start");
    checkpoints.save(first.resume("resumed"),first.revision(),"resume");
    var runs=new RunRegistry(Clock.systemUTC());runs.register(new AgentRunContext("original","session",root,project.id()));
    runs.transition("original",RunStatus.RUNNING,null);runs.transition("original",RunStatus.COMPLETED,null);
    runs.register(new AgentRunContext("resumed","session",root,project.id()));
    var tasks=new TaskManagerService(projects,runs,checkpoints,null,null,null);
    assertThat(tasks.list(project.id(),null,100,null).items()).extracting(TaskView::id).containsExactly("run:original");
    var task=tasks.get(project.id(),"session","run:original");
    assertThat(task.runId()).isEqualTo("resumed");assertThat(task.checkpointTaskId()).isEqualTo("checkpoint");
    assertThat(task.status()).isEqualTo("QUEUED");assertThat(task.progress()).isEqualTo(new TaskView.Progress(1,2));
    assertThat(checkpoints.get(project.id(),"checkpoint").revision()).isEqualTo(2);
    var restored=new TaskManagerService(projects,new RunRegistry(Clock.systemUTC()),new PersistentCheckpointRepository(source,new CheckpointProperties()),null,null,null);
    assertThat(restored.get(project.id(),"session","run:original").status()).isEqualTo("UNKNOWN");
    assertThat(restored.get(project.id(),"session","run:original").resumeSupported()).isTrue();
  }
  @Test void goalDependencyAndScheduleReuseDurableSourceStatesAndShowTheirParents() {
    var projects=new ProjectRegistry(root.resolve("projects.json"));var project=projects.resolve(root);
    var source=new SQLiteDataSource();source.setUrl("jdbc:sqlite:"+root.resolve("state.db"));var clock=Clock.systemUTC();
    var owner=new AgentRunContext("parent","session",root,project.id());
    var goals=new GoalRepository(source,clock);var goal=goals.create(owner,"objective","output.txt","a".repeat(64),3,4);
    var dependencies=new PersistentDependencyRepository(source,clock);
    var dependency=dependencies.create(owner,new DependencySpec(DependencySpec.Kind.USER_ANSWER,"confirm",null),Duration.ofMinutes(5),List.of());
    var schedules=new PersistentAgentScheduler(source,clock);String schedule;
    try(var binding=AgentRunScope.open(owner)){schedule=schedules.scheduleAfter(Duration.ofMinutes(5),"next iteration","session").id();}
    var runs=new RunRegistry(clock);runs.register(owner);
    var tasks=new TaskManagerService(projects,runs,null,goals,dependencies,schedules);
    assertThat(tasks.list(null,null,100,null).items()).hasSize(4);
    assertThat(tasks.get(project.id(),"session","dependency:"+dependency.id()).status()).isEqualTo("WAITING");
    assertThat(tasks.get(project.id(),"session","dependency:"+dependency.id()).parentId()).isEqualTo("run:parent");
    assertThat(tasks.get(project.id(),"session","schedule:"+schedule).status()).isEqualTo("QUEUED");
    assertThat(tasks.get(project.id(),"session","schedule:"+schedule).parentId()).isEqualTo("run:parent");
    assertThat(tasks.get(project.id(),"session","goal:"+goal.id()).resumeSupported()).isTrue();
    assertThat(tasks.get(project.id(),"session","run:parent").dependencyIds()).containsExactly(dependency.id());
    assertThat(new PersistentDependencyRepository(source,clock).creatorRun(project.id(),dependency.id())).isEqualTo("parent");
    assertThat(new PersistentAgentScheduler(source,clock).creatorRun(project.id(),schedule)).isEqualTo("parent");
  }
}
