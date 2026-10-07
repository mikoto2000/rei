package dev.mikoto2000.rei.application.task;

import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.application.run.RunRegistry;
import dev.mikoto2000.rei.core.dependency.*;
import static org.assertj.core.api.Assertions.*;

@Tag("integration")
class TaskSourcePaginationTest {
  @TempDir Path root;
  @Test void retainedGoalsSchedulesAndCheckpointsAreNotTruncatedToRecentSourceWindows() {
    var projects=new ProjectRegistry(root.resolve("projects.json"));var project=projects.resolve(root);
    var data=new SQLiteDataSource();data.setUrl("jdbc:sqlite:"+root.resolve("all.db"));var clock=Clock.systemUTC();
    var owner=new AgentRunContext("owner","session",root,project.id());
    var goals=new dev.mikoto2000.rei.goal.GoalRepository(data,clock);
    var schedules=new dev.mikoto2000.rei.temporal.PersistentAgentScheduler(data,clock);
    for(int i=0;i<270;i++) {
      var goal=goals.create(owner,"objective","output.txt","b".repeat(64),1,1);goals.verifiedWithoutRun(project.id(),goal.id());
      try(var binding=dev.mikoto2000.rei.core.chat.AgentRunScope.open(owner)) {
        var schedule=schedules.scheduleAfter(Duration.ofMinutes(5),"action","session");schedules.cancel(project.id(),schedule.id());
      }
    }
    var checkpoints=new dev.mikoto2000.rei.checkpoint.PersistentCheckpointRepository(data,new dev.mikoto2000.rei.checkpoint.CheckpointProperties());
    for(int i=0;i<1005;i++) {
      var checkpoint=dev.mikoto2000.rei.checkpoint.PersistentCheckpoint.initial(String.format("task-%04d",i),project.id(),"session",String.format("original-%04d",i),root,"request");
      var fields=checkpoints.fields(checkpoint);fields.put("status","COMPLETED");checkpoints.save(checkpoints.fields(fields),0,"start");
    }
    var tasks=new TaskManagerService(projects,new RunRegistry(clock),checkpoints,goals,null,schedules);
    var ids=new ArrayList<String>();String cursor=null;
    do {var page=tasks.list(project.id(),null,100,cursor);page.items().forEach(task->ids.add(task.id()));cursor=page.nextCursor();}while(cursor!=null);
    assertThat(ids).hasSize(1545).doesNotHaveDuplicates().isSorted();
  }
  @Test void paginationIncludesRetainedTerminalSourcesBeyondTheirExistingRecentListWindow() {
    var projects=new ProjectRegistry(root.resolve("projects.json"));var project=projects.resolve(root);
    var data=new SQLiteDataSource();data.setUrl("jdbc:sqlite:"+root.resolve("state.db"));var clock=Clock.systemUTC();
    var dependencies=new PersistentDependencyRepository(data,clock);var owner=new AgentRunContext("run","session",root,project.id());
    for(int i=0;i<270;i++) {
      var created=dependencies.create(owner,new DependencySpec(DependencySpec.Kind.USER_ANSWER,"question",null),Duration.ofMinutes(5),List.of());
      dependencies.cancel(project.id(),created.id());
    }
    var tasks=new TaskManagerService(projects,new RunRegistry(clock),null,null,dependencies,null);
    var ids=new ArrayList<String>();String cursor=null;
    do {var page=tasks.list(project.id(),null,47,cursor);assertThat(page.items()).hasSizeLessThanOrEqualTo(47);
      page.items().forEach(task->ids.add(task.id()));cursor=page.nextCursor();}while(cursor!=null);
    assertThat(ids).hasSize(270).doesNotHaveDuplicates().isSorted();
  }
}
