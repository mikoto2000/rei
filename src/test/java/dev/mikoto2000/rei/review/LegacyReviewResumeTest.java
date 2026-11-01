package dev.mikoto2000.rei.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@org.junit.jupiter.api.Tag("integration")
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
  void historicalReaderCannotDismissFindingsAfterWriteAuthorityMovesToResumedRun()
      throws Exception {
    var fixture = new LongRunFixture(directory);
    String id = fixture.create(6);
    var ledger = fixture.reopen();
    var span = new ReviewCoverageLedger.Span(0, 6, 1, 1, 0, 6);
    var range = new ReviewCoverageLedger.Range("fixture.java", "snapshot-v1", span, "hash");
    ledger.fetched(id, fixture.scope(), range);
    ledger.delivered(id, fixture.scope(), List.of(range));
    ledger.reviewed(
        id,
        fixture.scope(),
        range,
        "checked",
        List.of(
            new ReviewCoverageLedger.Finding(
                "F1",
                "P2",
                "fixture.java",
                "snapshot-v1",
                span,
                "source",
                "candidate",
                "VERIFIED")));
    var resumed = new ReviewCoverageLedger.Scope("project", "session", "new-run");
    ledger.resume(id, fixture.scope(), resumed);

    assertThatThrownBy(
            () ->
                ledger.dismissFindingTransition(
                    id, fixture.scope(), "F1", "fixture.java", null, "counterevidence"))
        .isInstanceOf(dev.mikoto2000.rei.toolcontract.ToolAccessDeniedException.class)
        .hasMessageContaining("REVIEW_ACCESS_DENIED");
    assertThat(ledger.get(id, fixture.scope()).findings().getFirst().verificationStatus())
        .isEqualTo("VERIFIED");

    var result =
        ledger.dismissFindingTransition(id, resumed, "F1", "fixture.java", null, "counterevidence");
    assertThat(result.changed()).isTrue();
    assertThat(ledger.get(id, fixture.scope()).findings().getFirst().verificationStatus())
        .isEqualTo("DISMISSED");
  }

  @Test
  void retiredRunCanReadHistoryButCannotPublishLateCoverage() throws Exception {
    var fixture = new LongRunFixture(directory);
    String id = fixture.create(6);
    var resumed = new ReviewCoverageLedger.Scope("project", "session", "new-run");
    fixture.reopen().resume(id, fixture.scope(), resumed);
    assertThatThrownBy(() -> fixture.review(id, 0, 3)).hasMessageContaining("REVIEW_ACCESS_DENIED");
    assertThat(fixture.reopen().get(id, fixture.scope()).ranges()).isEmpty();
    var range =
        new ReviewCoverageLedger.Range(
            "fixture.java", "snapshot-v1", new ReviewCoverageLedger.Span(0, 6, 1, 1, 0, 6), "hash");
    var ledger = fixture.reopen();
    ledger.fetched(id, resumed, range);
    ledger.delivered(id, resumed, List.of(range));
    ledger.reviewed(id, resumed, range, "checked", List.of());
    assertThat(ledger.get(id, fixture.scope()).status()).isEqualTo("COMPLETE");
  }
}
