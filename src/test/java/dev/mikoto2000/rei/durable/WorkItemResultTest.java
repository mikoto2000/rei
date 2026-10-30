package dev.mikoto2000.rei.durable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkItemResultTest {
  @TempDir Path temporary;

  @Test
  void onlyDeliveredEvidenceCommitsAndDuplicateSubmissionNeverOverwritesResult() throws Exception {
    var fixture = new TaskSnapshotTest();
    fixture.temporary = temporary;
    var store = fixture.store();
    var root = Files.createDirectory(temporary.resolve("repository"));
    Files.writeString(root.resolve("a.java"), "abcdef");
    var scope = new DurableTaskStore.Scope("project", "session", root.toString());
    var task = store.beginForRun(scope, "run", null, "WORKTREE", "", List.of(), 100, 1024);
    new SnapshotEnumerator(store).step(scope, task.id(), 100);
    var work = new WorkItemStore(store);
    var claim = work.claim(scope, task.id(), "run");
    assertThatThrownBy(() -> work.commit(scope, claim, "checked", List.of())).hasMessageContaining("SOURCE_NOT_DELIVERED");
    work.delivered(scope, claim, WorkItemStore.hash("abcdef".getBytes(StandardCharsets.UTF_8)));
    var invented = new WorkItemStore.Finding("f", "P2", 0, 3, "invented", "issue");
    assertThatThrownBy(() -> work.commit(scope, claim, "checked", List.of(invented))).hasMessageContaining("REVIEW_RESULT_INVALID");
    var finding = new WorkItemStore.Finding("f", "P2", 0, 3, "abc", "issue");
    var result = work.commit(scope, claim, "checked", List.of(finding));
    assertThat(work.commit(scope, claim, "checked", List.of(finding))).isEqualTo(result);
    assertThatThrownBy(() -> work.commit(scope, claim, "different", List.of(finding))).hasMessageContaining("RESULT_CONFLICT");
    var reopened = new WorkItemStore(fixture.store());
    assertThat(reopened.results(scope, task.id(), null, 10).entries()).containsExactly(result);
    assertThat(fixture.store().verify(scope, task.id()).complete()).isTrue();
    assertThat(fixture.store().status(scope, task.id()).state()).isEqualTo("COMPLETE");
    // Replayed prompt history may reconfirm a completed page; it must be an idempotent no-op.
    reopened.delivered(scope, claim, WorkItemStore.hash("abcdef".getBytes(StandardCharsets.UTF_8)));
  }
}
