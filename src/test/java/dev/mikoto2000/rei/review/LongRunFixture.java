package dev.mikoto2000.rei.review;

import dev.mikoto2000.rei.storage.StorageDatabase;
import dev.mikoto2000.rei.storage.StorageMigrationCoordinator;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Isolated persisted baseline shared by long-running review tests. */
final class LongRunFixture {
  private final Path directory;

  LongRunFixture(Path directory) throws IOException {
    this.directory = directory;
    try (var migration = new StorageMigrationCoordinator(directory)) {
      migration.prepare();
    }
  }

  ReviewCoverageLedger.Scope scope() {
    return new ReviewCoverageLedger.Scope("project", "session", "run");
  }

  ReviewCoverageLedger reopen() {
    return new ReviewCoverageLedger(new StorageDatabase(directory));
  }

  String create(int length) {
    return reopen()
        .create(
            scope(),
            "correctness",
            "v1",
            List.of(
                new ReviewCoverageLedger.Target(
                    "fixture.java", "snapshot-v1", 1, List.of(span(0, length)))))
        .id();
  }

  void review(String job, int start, int end) {
    var ledger = reopen();
    var range =
        new ReviewCoverageLedger.Range(
            "fixture.java", "snapshot-v1", span(start, end), "hash-" + start + "-" + end);
    ledger.fetched(job, scope(), range);
    ledger.delivered(job, scope(), List.of(range));
    ledger.reviewed(job, scope(), range, "checked", List.of());
  }

  private static ReviewCoverageLedger.Span span(int start, int end) {
    return new ReviewCoverageLedger.Span(start, end, 1, 1, start, end);
  }
}
