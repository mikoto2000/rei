package dev.mikoto2000.rei.review;

import static org.assertj.core.api.Assertions.*;

import dev.mikoto2000.rei.storage.*;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class ReviewCoverageLedgerTest {
  @TempDir Path dir;
  ReviewCoverageLedger ledger;
  final ReviewCoverageLedger.Scope scope = new ReviewCoverageLedger.Scope("project", "chat", "run");
  final ReviewCoverageLedger.Span first = new ReviewCoverageLedger.Span(0, 3, 1, 1, 0, 3);
  final ReviewCoverageLedger.Span second = new ReviewCoverageLedger.Span(3, 6, 1, 1, 3, 6);

  @BeforeEach
  void setup() throws Exception {
    try (var migration = new StorageMigrationCoordinator(dir)) {
      migration.prepare();
    }
    ledger = new ReviewCoverageLedger(new StorageDatabase(dir));
  }

  String create() {
    return ledger
        .create(
            scope,
            "technical correctness",
            "v1",
            List.of(
                new ReviewCoverageLedger.Target(
                    "a.md", "sha1", 1, List.of(new ReviewCoverageLedger.Span(0, 6, 1, 1, 0, 6)))))
        .id();
  }

  ReviewCoverageLedger.Range range(ReviewCoverageLedger.Span span) {
    return new ReviewCoverageLedger.Range("a.md", "sha1", span, "hash" + span.start());
  }

  @Test
  void fetchedDeliveredReviewedAreDistinctAndResumeRetainsCoverage() {
    String id = create();
    ledger.fetched(id, scope, range(first));
    assertThatThrownBy(() -> ledger.reviewed(id, scope, range(first), "checked", List.of()))
        .hasMessageContaining("REVIEW_RESULT_INVALID");
    ledger.delivered(id, scope, List.of(range(first)));
    ledger.reviewed(id, scope, range(first), "checked", List.of());
    assertThat(ledger.get(id, scope).status()).isEqualTo("REVIEW_PARTIAL");
    ledger = new ReviewCoverageLedger(new StorageDatabase(dir));
    assertThat(ledger.get(id, scope).ranges()).hasSize(1);
    ledger.fetched(id, scope, range(second));
    ledger.delivered(id, scope, List.of(range(second)));
    ledger.reviewed(id, scope, range(second), "checked", List.of());
    assertThat(ledger.get(id, scope).status()).isEqualTo("COMPLETE");
  }

  @Test
  void duplicatesDoNotAdvanceRevisionAndOtherConversationIsDenied() {
    String id = create();
    ledger.fetched(id, scope, range(first));
    long revision = ledger.get(id, scope).revision();
    ledger.fetched(id, scope, range(first));
    assertThat(ledger.get(id, scope).revision()).isEqualTo(revision);
    assertThatThrownBy(
            () -> ledger.get(id, new ReviewCoverageLedger.Scope("project", "other", "run")))
        .hasMessageContaining("ACCESS_DENIED");
  }

  @Test
  void explicitlyGrantedChildCanListTheSameJobWithoutCrossProjectAccess() {
    String id = create();
    var child = new ReviewCoverageLedger.Scope("project", "subagent:child", "child");
    ledger.grant(id, scope, child);
    assertThat(ledger.jobs(child)).containsExactly(id);
    assertThatThrownBy(
            () -> ledger.grant(id, scope, new ReviewCoverageLedger.Scope("other", "chat", "other")))
        .hasMessageContaining("ACCESS_DENIED");
  }

  @Test
  void checkpointResumeRetainsExactRangesAndFindings() {
    String id = create();
    ledger.fetched(id, scope, range(first));
    ledger.delivered(id, scope, List.of(range(first)));
    var resumed = new ReviewCoverageLedger.Scope("project", "chat", "resumed");
    ledger.resume(id, scope, resumed);
    assertThat(ledger.get(id, resumed).ranges()).isEqualTo(ledger.get(id, scope).ranges());
    assertThat(ledger.jobs(resumed)).containsExactly(id);
  }

  @Test
  void staleVersionNeverCompletesAndExternalSuccessIsUnverified() {
    String id = create();
    ledger.externalReport(id, scope, "success");
    assertThat(ledger.get(id, scope).status()).isEqualTo("REVIEW_UNVERIFIED");
    ledger.fetched(id, scope, range(first));
    ledger.delivered(id, scope, List.of(range(first)));
    ledger.reviewed(id, scope, range(first), "checked", List.of());
    ledger.invalidate(id, scope, "a.md", "sha2");
    assertThat(ledger.get(id, scope).status()).isEqualTo("STALE_VERSION");
    assertThatThrownBy(() -> ledger.fetched(id, scope, range(second)))
        .hasMessageContaining("STALE_VERSION");
  }
}
