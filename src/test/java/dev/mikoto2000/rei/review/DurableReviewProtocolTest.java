package dev.mikoto2000.rei.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mikoto2000.rei.core.Tools;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import dev.mikoto2000.rei.core.contextbudget.RawToolResultStore;
import dev.mikoto2000.rei.core.contextbudget.TokenEstimator;
import dev.mikoto2000.rei.storage.StorageDatabase;
import dev.mikoto2000.rei.storage.StorageMigrationCoordinator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@org.junit.jupiter.api.Tag("integration")
class DurableReviewProtocolTest {
  @TempDir Path temporary;

  @Test
  void frozenChunksRequireTrustedDeliveryAndResumeOnlyPendingRanges() throws Exception {
    var root = Files.createDirectory(temporary.resolve("repository"));
    String source = "abcdef🙂\n".repeat(1000);
    Files.writeString(root.resolve("a.java"), source);
    var data = temporary.resolve("data");
    try (var gate = new StorageMigrationCoordinator(data)) { gate.prepare(); }
    var ledger = new ReviewCoverageLedger(new StorageDatabase(data));
    var tools = new ReviewTools(ledger, new RawToolResultStore(data), new Tools(), TokenEstimator.conservative());
    var owner = new AgentRunContext("run", "session", root, "project");
    String task;
    ReviewTools.Page firstPage;
    try (var active = AgentRunScope.open(owner)) {
      task = tools.beginRepositoryReview("WORKTREE", List.of(), 100, 1048576L).id();
      tools.scanReviewSnapshot(task, 100);
      var claim = tools.claimReviewWorkItem(task, 256);
      firstPage = tools.readReviewWorkItem(claim.item().id(), claim.attempt());
      assertThat(firstPage.content()).isEqualTo(source.substring(0, firstPage.nextOffset()));
      assertThat(TokenEstimator.conservative().text(firstPage.content())).isLessThanOrEqualTo(256);
      assertThatThrownBy(() -> tools.submitReviewWorkItem(claim.item().id(), claim.attempt(), "checked", List.of()))
          .hasMessageContaining("SOURCE_NOT_DELIVERED");
      tools.confirmed(owner, List.of(firstPage));
      tools.submitReviewWorkItem(claim.item().id(), claim.attempt(), "checked", List.of());
      assertThat(tools.reviewTaskStatus(task).state()).isEqualTo("ACTIVE");
    }
    Files.writeString(root.resolve("a.java"), "changed live file");
    var resumed = new AgentRunContext("next-run", "session", root, "project");
    try (var active = AgentRunScope.open(resumed)) {
      assertThat(tools.listReviewTasks(null, 100).entries()).extracting(t -> t.id()).containsExactly(task);
      tools.resumeRepositoryReview(task);
      tools.confirmed(resumed, List.of(firstPage)); // retired receipt cannot poison replayed prompt history
      int end = firstPage.nextOffset();
      while (!tools.reviewTaskStatus(task).state().equals("COMPLETE")) {
        var claim = tools.claimReviewWorkItem(task, 256);
        assertThat(claim.item().start()).isEqualTo(end);
        var page = tools.readReviewWorkItem(claim.item().id(), claim.attempt());
        assertThat(page.content()).isEqualTo(source.substring(end, page.nextOffset()));
        tools.confirmed(resumed, List.of(page));
        tools.submitReviewWorkItem(claim.item().id(), claim.attempt(), "checked", List.of());
        end = page.nextOffset();
      }
      assertThat(end).isEqualTo(source.length());
      tools.requireComplete(resumed);
    }
  }
}
