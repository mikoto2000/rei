package dev.mikoto2000.rei.workcontext;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mikoto2000.rei.context.CancelledTaskHistory;
import dev.mikoto2000.rei.conversation.ConversationTurnStore;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class CancelledWorkContextTest {
  @ParameterizedTest
  @EnumSource(value = ConversationTurnStore.Status.class, names = {"FAILED", "CANCELLED"})
  void promptOmitsOldIntentAndRetainsEffectsAndFreshWorkWithoutChangingSavedContext(
      ConversationTurnStore.Status status) {
    var now = Instant.parse("2026-10-26T12:00:00Z");
    var archived = CancelledTaskHistory.from(List.of(
        new CancelledTaskHistory.Attempt("old", status.name(), now),
        new CancelledTaskHistory.Attempt("new", "RUNNING", now.plusSeconds(1))));
    var saved =
        new WorkContext(
            "project",
            1,
            now,
            now,
            null,
            List.of(
                item("old goal", WorkContext.Kind.CURRENT_WORK, "old", now),
                item("old next action", WorkContext.Kind.NEXT_ACTION, "old", now),
                item("unattributed pending", WorkContext.Kind.PENDING, null, now),
                item("changed-file.txt", WorkContext.Kind.ARTIFACT, "old", now),
                item("confirmed effect", WorkContext.Kind.COMPLETED_WORK, "old", now),
                item("new goal", WorkContext.Kind.CURRENT_WORK, "new", now)),
            Set.of("old"));
    var savedItems = List.copyOf(saved.items());
    var savedRuns = Set.copyOf(saved.processedRuns());
    var result = WorkContextAdvisor.forPrompt(saved, archived);
    assertThat(result.items())
        .extracting(WorkContext.Item::text)
        .containsExactly("changed-file.txt", "confirmed effect", "new goal");
    assertThat(result.items())
        .containsExactly(savedItems.get(3), savedItems.get(4), savedItems.get(5));
    assertThat(result).usingRecursiveComparison().ignoringFields("items").isEqualTo(saved);
    assertThat(saved.items()).containsExactlyElementsOf(savedItems);
    assertThat(saved.processedRuns()).containsExactlyInAnyOrderElementsOf(savedRuns);
  }

  private WorkContext.Item item(String text, WorkContext.Kind kind, String run, Instant now) {
    return new WorkContext.Item(
        text,
        kind,
        text,
        "reason",
        WorkContext.Status.OPEN,
        run == null
            ? List.of()
            : List.of(
                new WorkContext.Evidence(
                    text,
                    WorkContext.Origin.USER,
                    "chat",
                    run,
                    run,
                    null,
                    null,
                    null,
                    now,
                    now,
                    text)),
        now,
        now,
        false,
        null);
  }
}
