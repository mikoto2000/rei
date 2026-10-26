package dev.mikoto2000.rei.workcontext;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mikoto2000.rei.context.CancelledTaskHistory;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CancelledWorkContextTest {
  @Test
  void promptOmitsOldIntentAndRetainsEffectsAndFreshWorkWithoutChangingSavedContext() {
    var now = Instant.parse("2026-10-26T12:00:00Z");
    var cancelled = new CancelledTaskHistory(Set.of("old"), "old", Set.of("new"), List.of());
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
    var result = WorkContextAdvisor.forPrompt(saved, cancelled);
    assertThat(result.items())
        .extracting(WorkContext.Item::text)
        .containsExactly("changed-file.txt", "confirmed effect", "new goal");
    assertThat(saved.items()).hasSize(6);
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
