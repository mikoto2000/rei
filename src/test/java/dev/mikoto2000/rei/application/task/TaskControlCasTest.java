package dev.mikoto2000.rei.application.task;

import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.core.dependency.*;
import dev.mikoto2000.rei.goal.GoalRepository;
import dev.mikoto2000.rei.temporal.PersistentAgentScheduler;
import dev.mikoto2000.rei.application.state.OperationConflictException;
import static org.assertj.core.api.Assertions.*;

@Tag("integration")
class TaskControlCasTest {
  @TempDir Path root;
  @Test void staleCancellationCannotAffectNewGoalIterationOrUpdatedDependencyOrSchedule() {
    var data=new SQLiteDataSource();data.setUrl("jdbc:sqlite:"+root.resolve("state.db"));var clock=Clock.systemUTC();
    var owner=new AgentRunContext("owner","session",root,"project");
    var goals=new GoalRepository(data,clock);var goal=goals.create(owner,"objective","output.txt","a".repeat(64),3,4);
    var claim=goals.claim("project",goal.id());String run=goals.beginAttempt(claim);
    assertThatThrownBy(()->goals.cancel("project",goal.id(),null,0)).isInstanceOf(OperationConflictException.class);
    assertThat(goals.get("project",goal.id()).status()).isEqualTo("RUNNING");
    assertThat(goals.cancel("project",goal.id(),run,1).status()).isEqualTo("CANCELLED");
    assertThat(goals.get("project",goal.id()).maxLlmCalls()).isEqualTo(4);
    var dependencies=new PersistentDependencyRepository(data,clock);
    var dependency=dependencies.create(owner,new DependencySpec(DependencySpec.Kind.USER_ANSWER,"question",null),Duration.ofMinutes(5),List.of());
    dependencies.observe(dependency,DependencyState.RUNNING,"checking");
    assertThatThrownBy(()->dependencies.cancel("project",dependency.id(),0)).isInstanceOf(OperationConflictException.class);
    assertThat(dependencies.get("project",dependency.id()).state()).isEqualTo(DependencyState.RUNNING);
    assertThat(dependencies.cancel("project",dependency.id(),1).state()).isEqualTo(DependencyState.CANCELLED);
    var schedules=new PersistentAgentScheduler(data,clock);String id;
    try(var binding=AgentRunScope.open(owner)){id=schedules.scheduleAfter(Duration.ofMinutes(5),"next","session").id();}
    schedules.activate("project",id);
    assertThatThrownBy(()->schedules.cancel("project",id,1)).isInstanceOf(OperationConflictException.class);
    assertThat(schedules.get("project",id).status()).isEqualTo("SCHEDULED");
    schedules.cancel("project",id,2);assertThat(schedules.get("project",id).status()).isEqualTo("CANCELLED");
  }
}
