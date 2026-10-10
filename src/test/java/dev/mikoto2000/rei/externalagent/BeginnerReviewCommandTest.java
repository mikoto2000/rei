package dev.mikoto2000.rei.externalagent;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BeginnerReviewCommandTest {
  @Test void parsesAudienceKnowledgePlatformOrderAndMode() {
    var r = BeginnerReviewRequest.parse(new String[]{"beginner", "--root", "training with spaces", "--entry", "intro.mdx", "--chapter", "intro.mdx", "--chapter", "next.md", "--audience", "new developers", "--prerequisite", "Shell", "--os", "Windows", "--shell", "PowerShell", "--mode", "llm"});
    assertEquals("training with spaces", r.root());
    assertEquals(java.util.List.of("intro.mdx", "next.md"), r.order());
    assertEquals(java.util.Set.of("Shell"), r.prerequisites());
    assertEquals("PowerShell", r.shell());
    assertThrows(IllegalArgumentException.class, () -> BeginnerReviewRequest.parse(new String[]{"expert"}));
  }
  @Test void thinShellAdapterUsesExistingConversationQueueAndPreservesQuoting() {
    var conversations = mock(dev.mikoto2000.rei.application.session.ShellConversationService.class);
    assertEquals(0, new CommandLine(new BeginnerReviewCommand(conversations)).execute("beginner", "--root", "training with spaces", "--entry", "intro.md"));
    verify(conversations).submit("/material-review beginner --root \"training with spaces\" --entry intro.md");
  }
}
