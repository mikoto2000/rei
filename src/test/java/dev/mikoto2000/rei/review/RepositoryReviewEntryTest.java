package dev.mikoto2000.rei.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mikoto2000.rei.core.Tools;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import dev.mikoto2000.rei.core.contextbudget.RawToolResultStore;
import dev.mikoto2000.rei.core.contextbudget.TokenEstimator;
import dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException;
import dev.mikoto2000.rei.storage.StorageDatabase;
import dev.mikoto2000.rei.storage.StorageMigrationCoordinator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositoryReviewEntryTest {
  @TempDir Path temporary;

  @Test
  void registeredRepositoryReviewBlocksOwningRunEvenAfterInventorySeals() throws Exception {
    Path root = Files.createDirectory(temporary.resolve("repository"));
    Files.writeString(root.resolve("a.java"), "source");
    var data = temporary.resolve("data");
    try (var gate = new StorageMigrationCoordinator(data)) { gate.prepare(); }
    var ledger = new ReviewCoverageLedger(new StorageDatabase(data));
    var tools = new ReviewTools(ledger, new RawToolResultStore(data), new Tools(), TokenEstimator.conservative());
    var owner = new AgentRunContext("run", "session", root, "project");
    try (var active = AgentRunScope.open(owner)) {
      var task = tools.beginRepositoryReview("WORKTREE", List.of(), 100, 1048576L);
      assertThatThrownBy(() -> tools.requireComplete(owner)).isInstanceOf(ExecutionStoppedException.class);
      tools.scanReviewSnapshot(task.id(), 100);
      assertThat(tools.reviewTaskStatus(task.id()).enumeration()).isEqualTo("SEALED");
      assertThat(tools.listReviewTargets(task.id(), null, 100).entries()).hasSize(1);
      assertThatThrownBy(() -> tools.requireComplete(owner)).isInstanceOf(ExecutionStoppedException.class);
    }
    var unrelated = new AgentRunContext("another-run", "session", root, "project");
    try (var active = AgentRunScope.open(unrelated)) {
      tools.requireComplete(unrelated);
    }
  }
}
