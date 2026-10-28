package dev.mikoto2000.rei.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Long-running review contracts measured without a model or user data. */
class LongRunBaselineTest {
  @TempDir Path directory;

  @Test
  void missingMiddleCannotCompleteAndCommittedResultsSurviveReopen() throws Exception {
    var fixture = new LongRunFixture(directory);
    var job = fixture.create(9);
    fixture.review(job, 0, 3);
    fixture.review(job, 6, 9);
    assertThat(fixture.reopen().get(job, fixture.scope()).status()).isEqualTo("REVIEW_PARTIAL");
    fixture.review(job, 3, 6);
    var completed = fixture.reopen().get(job, fixture.scope());
    assertThat(completed.status()).isEqualTo("COMPLETE");
    assertThat(completed.ranges()).allMatch(ReviewCoverageLedger.Entry::reviewed);
    long revision = completed.revision();
    fixture.review(job, 3, 6);
    assertThat(fixture.reopen().get(job, fixture.scope()).revision()).isEqualTo(revision);
  }

  @Test
  void cancellationRejectsLateResultAndVersionChangeRejectsCompletion() throws Exception {
    var fixture = new LongRunFixture(directory);
    var job = fixture.create(6);
    fixture.review(job, 0, 3);
    fixture.reopen().stop(job, fixture.scope(), true);
    assertThatThrownBy(() -> fixture.review(job, 3, 6)).isInstanceOf(IllegalArgumentException.class);
    var changed = fixture.create(6);
    fixture.review(changed, 0, 3);
    fixture.reopen().invalidate(changed, fixture.scope(), "fixture.java", "changed");
    assertThatThrownBy(() -> fixture.review(changed, 3, 6))
        .hasMessageContaining("STALE_VERSION");
  }
}
