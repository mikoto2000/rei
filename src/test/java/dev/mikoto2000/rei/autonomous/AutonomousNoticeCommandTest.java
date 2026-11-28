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

class AutonomousNoticeCommandTest {
  private final AutonomousDraftNotifications notices = mock(AutonomousDraftNotifications.class);
  private final AutonomousDraftStore drafts = mock(AutonomousDraftStore.class);
  private final StringWriter output = new StringWriter();

  private int execute(String... args) {
    return new CommandLine(
            new AutonomousCommand(null, drafts, () -> null, () -> null, () -> notices))
        .setOut(new PrintWriter(output, true))
        .setErr(new PrintWriter(output, true))
        .execute(args);
  }

  @Test
  void listingAndAcknowledgmentUseOnlyTheNotificationPort() {
    var item =
        new AutonomousDraftNotifications.Item("notice-id", "draft-id", "OPEN", Instant.EPOCH);
    when(notices.listNotices(7)).thenReturn(List.of(item));
    when(notices.acknowledgeNotice("notice-id"))
        .thenReturn(
            new AutonomousDraftNotifications.Item(
                "notice-id", "draft-id", "ACKNOWLEDGED", Instant.EPOCH));
    assertThat(execute("notifications", "--limit", "7")).isZero();
    assertThat(output.toString()).contains("draft-id", "OPEN");
    assertThat(execute("ack-notice", "notice-id")).isZero();
    assertThat(output.toString()).contains("ACKNOWLEDGED");
    verify(notices).listNotices(7);
    verify(notices).acknowledgeNotice("notice-id");
    verifyNoInteractions(drafts);
  }

  @Test
  void acknowledgmentCannotCarryDraftMutationOrApprovalArguments() {
    assertThat(execute("ack-notice")).isEqualTo(2);
    assertThat(execute("ack-notice", "id", "--revision", "1")).isEqualTo(2);
    assertThat(execute("ack-notice", "id", "--text", "replacement")).isEqualTo(2);
    assertThat(execute("ack-notice", "id", "--reason", "private")).isEqualTo(2);
    assertThat(execute("ack-notice", "id", "--public-use")).isEqualTo(2);
    assertThat(execute("ack-notice", "id", "--actor", "human")).isEqualTo(2);
    verifyNoInteractions(notices, drafts);
  }
}
