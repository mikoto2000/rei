package dev.mikoto2000.rei.planning;
import java.time.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import dev.mikoto2000.rei.core.project.*;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.application.run.*;
import dev.mikoto2000.rei.application.task.*;
import dev.mikoto2000.rei.workcontext.*;
import dev.mikoto2000.rei.temporal.*;
import static org.assertj.core.api.Assertions.*;
@Tag("integration")
class DailyPlanningServiceTest {
  @TempDir Path root;
  static final Clock CLOCK=Clock.fixed(Instant.parse("2026-10-07T03:00:00Z"),ZoneOffset.UTC);
  @Test void onlyExplicitProjectsContributeAndSavedNextActionsRetainTheirUncertainty() throws Exception {
    var projects=new ProjectRegistry(root.resolve("projects.json"));var a=projects.resolve(Files.createDirectory(root.resolve("a")));var b=projects.resolve(Files.createDirectory(root.resolve("b")));
    var runs=new RunRegistry(CLOCK);runs.register(new AgentRunContext("a","session-a",a.root(),a.id()));runs.register(new AgentRunContext("b","session-b",b.root(),b.id()));
    var data=new DriverManagerDataSource("jdbc:sqlite:"+root.resolve("state.db"));var contexts=new WorkContextRepository(data);
    var item=new WorkContext.Item("next",WorkContext.Kind.NEXT_ACTION,"Check the saved result","suggestion",WorkContext.Status.UNCONFIRMED,List.of(),CLOCK.instant(),CLOCK.instant(),false,null,WorkContext.Origin.INFERENCE);
    contexts.save(new WorkContext(a.id(),1,CLOCK.instant(),CLOCK.instant(),new WorkContext.GitState(a.root().toString(),"main","a".repeat(40),CLOCK.instant()),List.of(item),Set.of()),0);
    var props=new DailyPlanningProperties();props.setProjects(Set.of(a.id()));props.setZone("Asia/Tokyo");
    var service=new DailyPlanningService(props,CLOCK,projects,new TaskManagerService(projects,runs,null,null,null,null),contexts,null,null,null,null,null);
    var plan=service.today(null);assertThat(plan.method()).isEqualTo("DETERMINISTIC");assertThat(plan.date()).isEqualTo(LocalDate.of(2026,10,7));
    assertThat(plan.projects()).extracting(DailyPlanningService.ProjectPlan::projectId).containsExactly(a.id());
    var entries=plan.projects().getFirst().items();assertThat(entries).extracting(DailyPlanningService.Item::id).contains("run:a","work-context:next").doesNotContain("run:b");
    var suggestion=entries.stream().filter(entry->entry.id().equals("work-context:next")).findFirst().orElseThrow();
    assertThat(suggestion.categories()).contains("Suggested Next");assertThat(suggestion.certainty()).isEqualTo("INFERENCE");assertThat(suggestion.status()).isEqualTo("UNCONFIRMED");
    assertThatThrownBy(()->service.today(List.of(b.id()))).isInstanceOf(ResourceNotFoundException.class);
    assertThatThrownBy(()->service.today(List.of("../../"))).isInstanceOf(ResourceNotFoundException.class);
    props.setMaxItemsPerProject(1);
    var capped=new DailyPlanningService(props,CLOCK,projects,new TaskManagerService(projects,runs,null,null,null,null),contexts,null,null,null,null,null).today(null);
    assertThat(capped.partial()).isTrue();assertThat(capped.projects().getFirst().items()).hasSize(1);assertThat(capped.projects().getFirst().warnings()).contains("item_limit_reached");
  }
  @Test void scheduledItemsUseRealDueTimesAndPaginationIsExplicitlyPartialAtTheConfiguredLimit() throws Exception {
    var projects=new ProjectRegistry(root.resolve("projects.json"));var project=projects.resolve(root);
    var runs=new RunRegistry(CLOCK);for(int i=0;i<105;i++)runs.register(new AgentRunContext("r"+i,"session",root,project.id()));
    var ds=new DriverManagerDataSource("jdbc:sqlite:"+root.resolve("state.db"));var schedules=new PersistentAgentScheduler(ds,CLOCK);
    String today,tomorrow;
    try(var scope=AgentRunScope.open(new AgentRunContext("creator","session",root,project.id()))) {
      today=schedules.scheduleAfter(Duration.ofHours(1),"Inspect today", "session").id();
      tomorrow=schedules.scheduleAfter(Duration.ofDays(1),"Inspect tomorrow", "session").id();
    }
    var props=new DailyPlanningProperties();props.setProjects(Set.of(project.id()));props.setZone("Asia/Tokyo");props.setMaxItemsPerProject(256);
    var service=new DailyPlanningService(props,CLOCK,projects,new TaskManagerService(projects,runs,null,null,null,schedules),null,null,null,schedules,null,null);
    var plan=service.today(null);assertThat(plan.partial()).isFalse();assertThat(plan.projects().getFirst().items()).hasSize(107);
    assertThat(plan.projects().getFirst().items().stream().filter(item->item.id().equals("schedule:"+today)).findFirst().orElseThrow().categories()).contains("Today","Scheduled");
    assertThat(plan.projects().getFirst().items().stream().filter(item->item.id().equals("schedule:"+tomorrow)).findFirst().orElseThrow().categories()).contains("Scheduled").doesNotContain("Today");
    props.setMaxTasksPerProject(100);var bounded=new DailyPlanningService(props,CLOCK,projects,new TaskManagerService(projects,runs,null,null,null,schedules),null,null,null,schedules,null,null).today(null);
    assertThat(bounded.partial()).isTrue();assertThat(bounded.projects().getFirst().warnings()).contains("task_limit_reached");
    assertThat(schedules.get(project.id(),today).status()).isEqualTo("PENDING");assertThat(schedules.get(project.id(),tomorrow).status()).isEqualTo("PENDING");
  }
  @Test void waitingOverdueBlockedResumedAndStaleAreSavedFactsAndNeverAdvanceExecution() {
    var projects=new ProjectRegistry(root.resolve("projects.json"));var project=projects.resolve(root);
    var ds=new DriverManagerDataSource("jdbc:sqlite:"+root.resolve("state.db"));
    var goals=new dev.mikoto2000.rei.goal.GoalRepository(ds,CLOCK);
    var dependencies=new dev.mikoto2000.rei.core.dependency.PersistentDependencyRepository(ds,CLOCK);
    var checkpoints=new dev.mikoto2000.rei.checkpoint.PersistentCheckpointRepository(ds,new dev.mikoto2000.rei.checkpoint.CheckpointProperties());
    var owner=new AgentRunContext("creator","session",root,project.id());
    var goal=goals.create(owner,"Verify the outcome","result.txt","a".repeat(64),2,10);
    var spec=new dev.mikoto2000.rei.core.dependency.DependencySpec(dev.mikoto2000.rei.core.dependency.DependencySpec.Kind.FILE_EXISTS,"result.txt",null);
    var waiting=dependencies.create(owner,spec,Duration.ofHours(1),List.of());
    var blocked=dependencies.create(owner,spec,Duration.ofHours(1),List.of(waiting.id()));
    var saved=checkpoints.save(dev.mikoto2000.rei.checkpoint.PersistentCheckpoint.initial("child-task",project.id(),"session","original",root,"Inspect the uncertain outcome"),0,"initial");
    checkpoints.save(saved.resume("resumed"),saved.revision(),"explicit-resume");
    var props=new DailyPlanningProperties();props.setProjects(Set.of(project.id()));
    var later=Clock.offset(CLOCK,Duration.ofDays(8));var tasks=new TaskManagerService(projects,new RunRegistry(later),checkpoints,goals,dependencies,null);
    var plan=new DailyPlanningService(props,later,projects,tasks,null,goals,dependencies,null,checkpoints,null).today(null);
    var items=plan.projects().getFirst().items();
    assertThat(items.stream().filter(item->item.id().equals("dependency:"+waiting.id())).findFirst().orElseThrow().categories()).contains("Waiting","Overdue");
    assertThat(items.stream().filter(item->item.id().equals("dependency:"+blocked.id())).findFirst().orElseThrow().categories()).contains("Blocked","Overdue");
    var currentGoal=items.stream().filter(item->item.id().equals("goal:"+goal.id())).findFirst().orElseThrow();assertThat(currentGoal.tags()).contains("CURRENT_GOAL","STALE");
    var resumed=items.stream().filter(item->item.id().equals("run:original")).findFirst().orElseThrow();assertThat(resumed.tags()).contains("RESUMED");assertThat(resumed.status()).isEqualTo("UNKNOWN");
    assertThat(goals.get(project.id(),goal.id())).isEqualTo(goal);assertThat(dependencies.get(project.id(),waiting.id()).version()).isZero();
    assertThat(checkpoints.get(project.id(),"child-task").revision()).isEqualTo(2);
  }
  @Test void observedActivityIsOnlyAReferenceAndRelocatedRootsFailClosed() throws Exception {
    var projects=new ProjectRegistry(root.resolve("projects.json"));var project=projects.resolve(root);
    var props=new DailyPlanningProperties();props.setProjects(Set.of(project.id()));
    var activity=org.mockito.Mockito.mock(dev.mikoto2000.rei.activity.ActivityWorkContextService.class);
    var link=new dev.mikoto2000.rei.activity.ActivityWorkContextService.Link("observation",CLOCK.instant(),null,null,null,null,null,null,1,List.of("next"),"OBSERVATION_CONTEXT",null);
    org.mockito.Mockito.when(activity.observationLinksBounded(project.id(),"today")).thenReturn(new dev.mikoto2000.rei.activity.ActivityWorkContextService.ObservationLinks(LocalDate.of(2026,10,7),"Asia/Tokyo",List.of(link),false,0));
    var service=new DailyPlanningService(props,CLOCK,projects,new TaskManagerService(projects,new RunRegistry(CLOCK),null,null,null,null),null,null,null,null,null,activity);
    var plan=service.today(null);assertThat(plan.projects().getFirst().items()).isEmpty();assertThat(plan.projects().getFirst().activity().provenance()).isEqualTo("OBSERVATION_ONLY");
    assertThat(plan.projects().getFirst().activity().references()).extracting(DailyPlanningService.Observation::recordId).containsExactly("observation");
    projects.relocate(project.id(),Files.createDirectory(root.resolve("moved")));
    assertThatThrownBy(()->service.today(null)).isInstanceOf(ResourceNotFoundException.class);
  }
  @Test void calendarUsesDstDayBoundariesRatherThanTwentyFourHoursAndInvalidConfigurationIsRejected() {
    var projects=new ProjectRegistry(root.resolve("projects.json"));var project=projects.resolve(root);
    var clock=Clock.fixed(Instant.parse("2026-11-01T05:30:00Z"),ZoneOffset.UTC);
    var ds=new DriverManagerDataSource("jdbc:sqlite:"+root.resolve("state.db"));var schedules=new PersistentAgentScheduler(ds,clock);
    String lateToday,tomorrow;
    try(var scope=AgentRunScope.open(new AgentRunContext("creator","session",root,project.id()))) {
      lateToday=schedules.scheduleAfter(Duration.ofHours(23),"late today","session").id();
      tomorrow=schedules.scheduleAfter(Duration.ofHours(24),"tomorrow","session").id();
    }
    var props=new DailyPlanningProperties();props.setProjects(Set.of(project.id()));props.setZone("America/New_York");
    var plan=new DailyPlanningService(props,clock,projects,new TaskManagerService(projects,new RunRegistry(clock),null,null,null,schedules),null,null,null,schedules,null,null).today(null);
    assertThat(plan.projects().getFirst().items().stream().filter(item->item.id().equals("schedule:"+lateToday)).findFirst().orElseThrow().categories()).contains("Today");
    assertThat(plan.projects().getFirst().items().stream().filter(item->item.id().equals("schedule:"+tomorrow)).findFirst().orElseThrow().categories()).doesNotContain("Today");
    props.setZone("invalid-zone");assertThatThrownBy(props::validate).isInstanceOf(IllegalArgumentException.class);
    var empty=new DailyPlanningProperties();assertThatThrownBy(empty::validate).isInstanceOf(IllegalArgumentException.class);
  }
}
