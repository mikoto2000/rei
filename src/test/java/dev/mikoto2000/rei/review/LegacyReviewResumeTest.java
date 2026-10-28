package dev.mikoto2000.rei.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LegacyReviewResumeTest {
  @TempDir Path directory;

  @Test
  void moreThanOneHundredResumesPreserveResultsWithoutGrowingActiveParticipants() throws Exception {
    var fixture = new LongRunFixture(directory);
    String id = fixture.create(6);
    fixture.review(id, 0, 3);
    var previous = fixture.scope();
    for (int i = 0; i < 101; i++) {
      var resumed = new ReviewCoverageLedger.Scope("project", "session", "resumed-" + i);
      fixture.reopen().resume(id, previous, resumed);
      fixture.reopen().resume(id, previous, resumed);
      previous = resumed;
    }
    var ledger = fixture.reopen();
    assertThat(ledger.get(id, previous).owner()).isEqualTo(previous);
    assertThat(ledger.get(id, previous).participants()).containsExactly(previous);
    assertThat(ledger.get(id, fixture.scope()).ranges()).hasSize(1);
    assertThat(ledger.jobs(previous)).containsExactly(id);
    var child = new ReviewCoverageLedger.Scope("project", "subagent:child", "child");
    ledger.grant(id, previous, child);
    assertThat(ledger.get(id, child).ranges().getFirst().reviewed()).isTrue();
  }

  @Test
  void retiredRunCanReadHistoryButCannotPublishLateCoverage() throws Exception {
    var fixture = new LongRunFixture(directory);
    String id = fixture.create(6);
    var resumed = new ReviewCoverageLedger.Scope("project", "session", "new-run");
    fixture.reopen().resume(id, fixture.scope(), resumed);
    assertThatThrownBy(() -> fixture.review(id, 0, 3)).hasMessageContaining("REVIEW_ACCESS_DENIED");
    assertThat(fixture.reopen().get(id, fixture.scope()).ranges()).isEmpty();
    var range = new ReviewCoverageLedger.Range("fixture.java", "snapshot-v1", new ReviewCoverageLedger.Span(0, 6, 1, 1, 0, 6), "hash");
    var ledger = fixture.reopen();
    ledger.fetched(id, resumed, range);
    ledger.delivered(id, resumed, List.of(range));
    ledger.reviewed(id, resumed, range, "checked", List.of());
    assertThat(ledger.get(id, fixture.scope()).status()).isEqualTo("COMPLETE");
  }
}
