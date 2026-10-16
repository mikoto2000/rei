package dev.mikoto2000.rei.application.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mikoto2000.rei.application.state.OperationConflictException;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import dev.mikoto2000.rei.core.dependency.DependencySpec;
import dev.mikoto2000.rei.core.dependency.DependencyState;
import dev.mikoto2000.rei.core.dependency.PersistentDependencyRepository;
import dev.mikoto2000.rei.goal.GoalRepository;
import dev.mikoto2000.rei.temporal.PersistentAgentScheduler;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

@Tag("integration")
class TaskControlCasTest {
  @TempDir Path root;

  @Test
  void staleCancellationCannotAffectNewGoalIterationOrUpdatedDependencyOrSchedule() {
    var data = new SQLiteDataSource();
    data.setUrl("jdbc:sqlite:" + root.resolve("state.db"));
    var clock = Clock.systemUTC();
    var owner = new AgentRunContext("owner", "session", root, "project");
    var goals = new GoalRepository(data, clock);
    var goal = goals.create(owner, "objective", "output.txt", "a".repeat(64), 3, 4);
    var claim = goals.claim("project", goal.id());
    String run = goals.beginAttempt(claim);
    assertThatThrownBy(() -> goals.cancel("project", goal.id(), null, 0))
        .isInstanceOf(OperationConflictException.class);
    assertThat(goals.get("project", goal.id()).status()).isEqualTo("RUNNING");
    assertThat(goals.cancel("project", goal.id(), run, 1).status()).isEqualTo("CANCELLED");
    assertThat(goals.get("project", goal.id()).maxLlmCalls()).isEqualTo(4);
    var dependencies = new PersistentDependencyRepository(data, clock);
    var dependency =
        dependencies.create(
            owner,
            new DependencySpec(DependencySpec.Kind.USER_ANSWER, "question", null),
            Duration.ofMinutes(5),
            List.of());
    dependencies.observe(dependency, DependencyState.RUNNING, "checking");
    assertThatThrownBy(() -> dependencies.cancel("project", dependency.id(), 0))
        .isInstanceOf(OperationConflictException.class);
    assertThat(dependencies.get("project", dependency.id()).state())
        .isEqualTo(DependencyState.RUNNING);
    assertThat(dependencies.cancel("project", dependency.id(), 1).state())
        .isEqualTo(DependencyState.CANCELLED);
    var schedules = new PersistentAgentScheduler(data, clock);
    String id;
    try (var binding = AgentRunScope.open(owner)) {
      id = schedules.scheduleAfter(Duration.ofMinutes(5), "next", "session").id();
    }
    schedules.activate("project", id);
    assertThatThrownBy(() -> schedules.cancel("project", id, 1))
        .isInstanceOf(OperationConflictException.class);
    assertThat(schedules.get("project", id).status()).isEqualTo("SCHEDULED");
    schedules.cancel("project", id, 2);
    assertThat(schedules.get("project", id).status()).isEqualTo("CANCELLED");
  }
}
