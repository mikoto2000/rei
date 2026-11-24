package dev.mikoto2000.rei.llm.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ToolSearchAndAvailabilityTest {
  @Test
  void japanesePurposeSelectsReadOrWriteRatherThanTheEntireCategory() throws Exception {
    var catalog = ToolCatalog.standard(ToolCatalogTest.all());
    var search = new ToolSearchService();
    assertThat(search.search(catalog, "明日の予定を確認", 3))
        .containsExactly(catalog.operationSetFor("googleCalendarListEventsForDate"));
    assertThat(search.search(catalog, "予定を作成", 3))
        .containsExactly(catalog.operationSetFor("googleCalendarCreateEvent"));
    assertThat(catalog.toolsForSets(Set.of(catalog.operationSetFor("translatePaper"))))
        .containsExactlyInAnyOrder("translatePaper", "getPaperTranslationChunk");
  }

  private ToolCatalog catalog() {
    return ToolCatalog.of(
        List.of("now", "first", "second", "third").stream().map(ToolCatalogTest::callback).toList(),
        List.of(
            new ToolCatalog.Entry("now", true, "now", "日時", List.of()),
            new ToolCatalog.Entry("first", false, "shared", "予定 calendar", List.of()),
            new ToolCatalog.Entry("second", false, "shared", "予定 calendar", List.of()),
            new ToolCatalog.Entry("third", false, "other", "予定 calendar", List.of())));
  }

  @Test
  void aggregatesBeforeLimitingAndUsesMaxNotSumOfScores() {
    var c = catalog();
    assertThat(new ToolSearchService().search(c, "予定", 2)).containsExactly("shared", "other");
  }

  @Test
  void neverChangesOldSnapshotAndProtectsTheWholeExecutingBatch() {
    var c = catalog();
    var state = new RunToolAvailability(c, 4, 24);
    var initial = state.names();
    assertThat(state.add(Set.of("shared")).status()).isEqualTo("added");
    assertThat(initial).containsExactlyInAnyOrder("now", "searchTools");
    try (var lease = state.protect(Set.of("first", "second"))) {
      var before = state.saved();
      assertThat(state.add(Set.of("other")).status()).isEqualTo("capacity_exceeded");
      assertThat(state.saved()).isEqualTo(before);
    }
    assertThat(state.add(Set.of("other")).removed()).containsExactlyInAnyOrder("first", "second");
  }

  @Test
  void ledgerRetentionAndExecutionProtectionAreIndependent() {
    var state = new RunToolAvailability(catalog(), 4, 24);
    state.add(Set.of("shared"));
    state.setRetention(() -> Set.of("first"));
    assertThat(state.add(Set.of("other")).status()).isEqualTo("capacity_exceeded");
    state.setRetention(Set::of);
    assertThat(state.add(Set.of("other")).status()).isEqualTo("added");
  }

  @Test
  void unreadableLedgerFailsClosedWithoutAdvancingVersionOrLru() {
    var state = new RunToolAvailability(catalog(), 4, 24);
    var before = state.saved();
    state.setRetention(() -> {
      throw new IllegalStateException("storage unavailable");
    });
    assertThat(state.add(Set.of("other")).status()).isEqualTo("retention_unavailable");
    assertThat(state.saved()).isEqualTo(before);
  }

  @Test
  void cyclesAndSharedDependenciesCountUniqueToolsAndCannotBePartiallyAdded() {
    var names = List.of("now", "a", "b");
    var c =
        ToolCatalog.of(
            names.stream().map(ToolCatalogTest::callback).toList(),
            List.of(
                new ToolCatalog.Entry("now", true, "now", "", List.of()),
                new ToolCatalog.Entry("a", false, "a", "", List.of("b")),
                new ToolCatalog.Entry("b", false, "b", "", List.of("a"))));
    assertThat(c.toolsForSets(Set.of("a", "b"))).containsExactlyInAnyOrder("a", "b");
    var tooSmall = new RunToolAvailability(c, 4, 1);
    var before = tooSmall.saved();
    assertThat(tooSmall.add(Set.of("a")).status()).isEqualTo("selection_too_large");
    assertThat(tooSmall.saved()).isEqualTo(before);
    var fits = new RunToolAvailability(c, 4, 2);
    assertThat(fits.add(Set.of("a", "b")).added()).containsExactlyInAnyOrder("a", "b");
    assertThat(fits.add(Set.of("a")).status()).isEqualTo("already_available");
  }
}
