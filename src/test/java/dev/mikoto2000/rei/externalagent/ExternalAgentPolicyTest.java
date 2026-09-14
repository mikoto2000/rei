package dev.mikoto2000.rei.externalagent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class ExternalAgentPolicyTest {
  @TempDir Path root;
  @Test void parsesOnlySupportedCommand() {
    assertNull(ExternalAgentCommandRequest.parse("/agent codex review").target());
    assertEquals("docs/a b.md", ExternalAgentCommandRequest.parse("/agent codex review docs/a b.md").target());
    for (String text : new String[]{"/agent", "/agent codex", "/agent foo review", "/agent codex implement"})
      assertThrows(IllegalArgumentException.class, () -> ExternalAgentCommandRequest.parse(text));
  }
  @Test void requiresAnExplicitRequestAndRejectsNegationAndMentionOnly() {
    for (String text : new String[]{"Codex にこの設計をレビューさせて", "今の案について Codex の意見も聞いて", "Please ask Codex to review this", "/agent codex review", "/agent codex review docs/examples.md"})
      assertTrue(ExternalAgentAuthorization.explicitRequest(text), text);
    for (String text : new String[]{"この設計レビューして", "Codex とは何ですか", "Do not use Codex to review this", "Codex は使わずレビューして", "Codex にレビューさせないで",
        "「Codex にレビューさせて」を英訳して", "Codex にレビューさせてというコマンドはある？", "Explain how to ask Codex to review this"})
      assertFalse(ExternalAgentAuthorization.explicitRequest(text), text);
  }
  @Test void canonicalTargetMustRemainInsideProject() throws Exception {
    Path file = Files.writeString(root.resolve("design.md"), "design");
    assertEquals(file.toRealPath(), ExternalAgentRequest.resolveTarget(root, "design.md"));
    assertThrows(IllegalArgumentException.class, () -> ExternalAgentRequest.resolveTarget(root, "../outside"));
    Path outside = Files.createTempDirectory("rei-external-outside");
    try {
      Files.createSymbolicLink(root.resolve("escape"), outside);
      assertThrows(IllegalArgumentException.class, () -> ExternalAgentRequest.resolveTarget(root, "escape"));
    } finally { Files.deleteIfExists(outside); }
  }
}
