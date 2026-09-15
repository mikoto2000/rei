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
      createSymbolicLink(root.resolve("escape"), outside);
      assertThrows(IllegalArgumentException.class, () -> ExternalAgentRequest.resolveTarget(root, "escape"));
      assertThrows(IllegalArgumentException.class,
          () -> ExternalAgentRequest.resolveTarget(root, root.resolve("escape").toAbsolutePath().toString()));
    } finally { Files.deleteIfExists(outside); }
  }
  @Test void acceptsAbsoluteFilesDirectoriesAndProjectRoot() throws Exception {
    Path directory = Files.createDirectories(root.resolve("docs with spaces"));
    Path file = Files.writeString(directory.resolve("design.md"), "design");
    for (Path target : new Path[]{file, directory, root}) {
      assertEquals(target.toRealPath(), ExternalAgentRequest.resolveTarget(root, target.toAbsolutePath().toString()));
      assertEquals(target.toRealPath(), ExternalAgentRequest.resolveTarget(root, target.toAbsolutePath().toString().replace('\\', '/')));
    }
  }
  @Test void rejectsExistingAbsoluteTargetsOutsideProject() throws Exception {
    Path project = Files.createDirectory(root.resolve("project"));
    Path sibling = Files.createDirectory(root.resolve("project-other"));
    assertThrows(IllegalArgumentException.class,
        () -> ExternalAgentRequest.resolveTarget(project, sibling.toAbsolutePath().toString()));
    assertThrows(IllegalArgumentException.class,
        () -> ExternalAgentRequest.resolveTarget(project, "../project-other"));
  }
  @Test void acceptsAbsoluteTargetThroughProjectAlias() throws Exception {
    Path project = Files.createDirectory(root.resolve("project"));
    Path file = Files.writeString(project.resolve("design.md"), "design");
    Path alias = createSymbolicLink(root.resolve("alias"), project);
    assertEquals(file.toRealPath(), ExternalAgentRequest.resolveTarget(alias, alias.resolve("design.md").toAbsolutePath().toString()));
  }
  @Test void distinguishesMissingAndInvalidTargets() {
    assertEquals("Current project or target does not exist", assertThrows(IllegalArgumentException.class,
        () -> ExternalAgentRequest.resolveTarget(root, root.resolve("missing.md").toAbsolutePath().toString())).getMessage());
    assertTrue(assertThrows(IllegalArgumentException.class,
        () -> ExternalAgentRequest.resolveTarget(root, "\u0000")).getMessage().contains("invalid"));
  }
  private static Path createSymbolicLink(Path link, Path target) throws java.io.IOException {
    try {
      return Files.createSymbolicLink(link, target);
    } catch (FileSystemException | UnsupportedOperationException error) {
      org.junit.jupiter.api.Assumptions.abort("Symbolic links are unavailable: " + error.getMessage());
      throw error;
    }
  }
  @Test void acceptsExplicitJapaneseReviewRequestsWithDifferentParticlesAndWordOrder() {
    for (String text : new String[]{"Codex で design.md をレビューしてください", "Codex を使ってレビューして",
        "design.md のレビューを Codex に依頼してください", "Codex にレビューをお願いします",
        "更新した design.md を Codex にレビューしてもらって、指摘を修正してください"})
      assertTrue(ExternalAgentAuthorization.explicitRequest(text), text);
  }
  @Test void acceptsExplicitDelegationFollowUpWithoutRepeatingTheWordReview() {
    assertTrue(ExternalAgentAuthorization.explicitRequest("もう一回 Codex に依頼を出して、指摘を修正してください"));
    assertTrue(ExternalAgentAuthorization.explicitRequest("もう一度 Codex に依頼して"));
    for (String text : new String[]{"もう一回依頼を出して", "Codex に依頼を出した", "Codex に依頼を出さないで",
        "Codex に依頼しないで", "Codex に依頼を出してという文章を説明して", "「Codex に依頼を出して」を翻訳して"})
      assertFalse(ExternalAgentAuthorization.explicitRequest(text), text);
  }
}
