package dev.mikoto2000.rei.autonomous;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class AutonomousHistoryCommandTest {
  private final AutonomousHistory history = mock(AutonomousHistory.class);
  private final AutonomousDraftStore drafts = mock(AutonomousDraftStore.class);
  private final AutonomousPublicSources sources = mock(AutonomousPublicSources.class);
  private final AutonomousGenerationService generation = mock(AutonomousGenerationService.class);
  private final AutonomousPublicationService publication = mock(AutonomousPublicationService.class);
  private final AutonomousDraftNotifications notices = mock(AutonomousDraftNotifications.class);
  private final StringWriter output = new StringWriter();

  private int execute(String... arguments) {
    return new CommandLine(
            new AutonomousCommand(
                sources, drafts, () -> generation, () -> publication, () -> notices, () -> history))
        .setOut(new PrintWriter(output, true))
        .setErr(new PrintWriter(output, true))
        .execute(arguments);
  }

  @Test
  void publishedAndGenerationHistoryUseOnlyTheReadPort() {
    when(history.published(7)).thenReturn(List.of());
    when(history.considerations(5))
        .thenReturn(
            List.of(
                new AutonomousHistory.Opportunity(
                    "scheduled:bluesky:2026-11-28T03:00:00Z",
                    AutonomousDraft.Trigger.SCHEDULED,
                    AutonomousHistory.Kind.NEW,
                    AutonomousHistory.Status.SKIPPED,
                    Instant.EPOCH,
                    Instant.EPOCH,
                    "no_suitable_topic",
                    null)));
    assertThat(execute("published", "--limit", "7")).isZero();
    assertThat(execute("history", "--limit", "5")).isZero();
    assertThat(output.toString()).contains("SKIPPED", "no_suitable_topic").doesNotContain("token");
    verify(history).published(7);
    verify(history).considerations(5);
    verifyNoInteractions(drafts, sources, generation, publication, notices);
  }

  @Test
  void historyRejectsDraftMutationAndApprovalArguments() {
    for (String action : List.of("published", "history")) {
      assertThat(execute(action, "id")).isEqualTo(2);
      assertThat(execute(action, "--text", "replacement")).isEqualTo(2);
      assertThat(execute(action, "--revision", "1")).isEqualTo(2);
      assertThat(execute(action, "--reason", "private")).isEqualTo(2);
      assertThat(execute(action, "--public-use")).isEqualTo(2);
    }
    verifyNoInteractions(history, drafts, sources, generation, publication, notices);
  }

  @Test
  void defaultLimitIsBoundedAndPrivateStoreErrorsAreNotPrinted() {
    when(history.considerations(20)).thenThrow(new IllegalStateException("PRIVATE_STORE_ERROR"));
    assertThat(execute("history")).isEqualTo(2);
    assertThat(output.toString()).doesNotContain("PRIVATE_STORE_ERROR");
    verify(history).considerations(20);
    verifyNoInteractions(drafts, sources, generation, publication, notices);
  }
}
