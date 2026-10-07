package dev.mikoto2000.rei.externalagent;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MaterialReviewCommandTest {
  @Test void parsesOptionalTargetAndPreservesGenericReview() {
    for (String target : new String[]{null, ".", "docs", "training with spaces"}) {
      var command = ExternalAgentCommandRequest.parse("/agent codex material-review" + (target == null ? "" : " " + target));
      assertEquals("material-review", command.action());
      assertEquals(target, command.target());
      assertTrue(ExternalAgentAuthorization.explicitRequest("/agent codex material-review", ExternalAgentRequest.Agent.CODEX));
    }
    assertEquals("review", ExternalAgentCommandRequest.parse("/agent codex review .").action());
    assertThrows(IllegalArgumentException.class, () -> ExternalAgentCommandRequest.parse("/agent codex unknown ."));
    assertThrows(IllegalArgumentException.class, () -> ExternalAgentCommandRequest.parse("/agent claude material-review ."));
  }
  @Test void shellQueuesMaterialReview() {
    var shell = mock(dev.mikoto2000.rei.application.session.ShellConversationService.class);
    assertEquals(0, new CommandLine(new ExternalAgentCommand(shell)).execute("codex", "material-review", "docs"));
    verify(shell).submit("/agent codex material-review docs");
  }
}
