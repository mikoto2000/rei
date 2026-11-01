package dev.mikoto2000.rei.goal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.contextbudget.ContextCompressionConfiguration;
import dev.mikoto2000.rei.review.ReviewCoverageLedger;
import dev.mikoto2000.rei.storage.StorageDatabase;
import dev.mikoto2000.rei.storage.StorageMigrationCoordinator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@org.junit.jupiter.api.Tag("integration")
class DurableGoalGateTest {
  @TempDir Path temporary;

  @Test
  void existingFileEvidenceCannotCompleteGoalWithUnenumeratedRepositoryTask() throws Exception {
    var root = Files.createDirectory(temporary.resolve("repository"));
    Files.writeString(root.resolve("done.json"), "{\"ok\":true}");
    var data = temporary.resolve("data");
    try (var migration = new StorageMigrationCoordinator(data)) { migration.prepare(); }
    var goals = new GoalRepository(new DriverManagerDataSource("jdbc:sqlite:" + data.resolve("goals.db")), Clock.systemUTC());
    var owner = new AgentRunContext("run", "session", root, "project");
    var goal = goals.create(owner, "review", List.of(new GoalRepository.FileCriterion("done.json", "", "/ok", "true")), 3, 100);
    var verifier = new FileGoalVerifier();
    assertThat(verifier.verify(goal).satisfied()).isTrue();
    var ledger = new ReviewCoverageLedger(new StorageDatabase(data));
    var factory = new StaticListableBeanFactory();
    factory.addBean("verifier", verifier);
    new ContextCompressionConfiguration().durableGoalVerification(factory.getBeanProvider(FileGoalVerifier.class), ledger).afterSingletonsInstantiated();
    var scope = new dev.mikoto2000.rei.durable.DurableTaskStore.Scope("project", "session", root.toString());
    ledger.durableTasks().beginForRun(scope, owner.runId(), goal.id(), "WORKTREE", "", List.of(), 100, 1024);
    assertThat(verifier.verify(goal).satisfied()).isFalse();
    assertThat(verifier.verify(goal).reason()).isEqualTo("durable_task:ENUMERATION_SCANNING");
  }
}
