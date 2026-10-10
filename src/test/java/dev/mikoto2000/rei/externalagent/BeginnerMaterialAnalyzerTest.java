package dev.mikoto2000.rei.externalagent;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class BeginnerMaterialAnalyzerTest {
  @TempDir Path root;
  @Test void explicitOrderWinsAndReferenceLinksAreNotRequiredChapters() throws Exception {
    Files.writeString(root.resolve("intro.md"), "# Intro\n[Reference](extra.md)\n");
    Files.writeString(root.resolve("next.mdx"), "# Next\n");
    Files.writeString(root.resolve("extra.md"), "# Extra\n");
    var result = new BeginnerMaterialAnalyzer().analyze(root, "intro.md", List.of("next.mdx", "intro.md"), Set.of(), 10000);
    assertEquals(List.of("next.mdx", "intro.md"), result.chapters().stream().map(c -> c.file()).toList());
    assertEquals(List.of("extra.md"), result.unreadFiles());
  }
  @Test void onlyExplanationsAddKnowledgeAndPreserveLineEvidence() throws Exception {
    Files.writeString(root.resolve("a.md"), "# A\nMention of Docker\n## Concepts\n- Container: an isolated process\n## Prerequisites\n- Docker\n## Exercises\nRun this\n## Expected results\nSuccess\n");
    var result = new BeginnerMaterialAnalyzer().analyze(root, "a.md", List.of(), Set.of("Shell"), 10000);
    var chapter = result.chapters().getFirst();
    assertEquals(Set.of("Shell"), chapter.knowledgeBefore());
    assertEquals(List.of("Container"), chapter.explained().stream().map(e -> e.value()).toList());
    assertEquals(4, chapter.explained().getFirst().line());
    assertEquals("Docker", chapter.required().getFirst().value());
    assertFalse(chapter.knowledgeAfter().contains("Docker"));
    assertEquals(1, chapter.exercises().size());
    assertEquals(1, chapter.expectedResults().size());
  }
  @Test void partialAndMissingChaptersNeverBecomeReviewed() throws Exception {
    Files.writeString(root.resolve("a.md"), "# A\n" + "x".repeat(100));
    var result = new BeginnerMaterialAnalyzer().analyze(root, "a.md", List.of("a.md", "missing.md"), Set.of(), 10);
    assertEquals(BeginnerMaterialAnalyzer.ReadState.PARTIAL, result.chapters().getFirst().state());
    assertEquals(BeginnerMaterialAnalyzer.ReadState.FAILED, result.chapters().get(1).state());
    assertFalse(result.complete());
  }
  @Test void navigationJsonSuppliesOrderWithoutExecutingConfigOrMaterial() throws Exception {
    Files.writeString(root.resolve("a.md"), "# A\n");
    Files.writeString(root.resolve("b.md"), "# B\n");
    Files.writeString(root.resolve("_meta.json"), "[\"b\",\"a\"]");
    var result = new BeginnerMaterialAnalyzer().analyze(root, "a.md", List.of(), Set.of(), 10000);
    assertEquals(List.of("b.md", "a.md"), result.chapters().stream().map(c -> c.file()).toList());
  }
  @Test void refusesEscapeAndReportsMissingExerciseFiles() throws Exception {
    Files.writeString(root.resolve("a.md"), "# A\n## Required files\n- absent.txt\n- ../secret.txt\n");
    var result = new BeginnerMaterialAnalyzer().analyze(root, "a.md", List.of(), Set.of(), 10000);
    assertEquals(BeginnerMaterialAnalyzer.ReferenceState.MISSING, result.chapters().getFirst().files().getFirst().state());
    assertEquals(BeginnerMaterialAnalyzer.ReferenceState.OUTSIDE_ROOT, result.chapters().getFirst().files().get(1).state());
    assertThrows(IllegalArgumentException.class, () -> new BeginnerMaterialAnalyzer().analyze(root, "../outside.md", List.of(), Set.of(), 100));
  }
  @Test void cancellationPropagatesWithoutReportingChapterFailure() throws Exception {
    Files.writeString(root.resolve("a.md"), "# A\n");
    try {
      Thread.currentThread().interrupt();
      assertThrows(java.util.concurrent.CancellationException.class,
          () -> new BeginnerMaterialAnalyzer().analyze(root, "a.md", List.of(), Set.of(), 100));
    } finally { Thread.interrupted(); }
  }
  @Test void codeAndMentionsAreNotExplanationsAndKnowledgeFlowsAcrossChapters() throws Exception {
    Files.writeString(root.resolve("a.md"), "## Concepts\n- Mention\n```md\n## Concepts\n- Fake: code example\n```\n- Real: explanation\n");
    Files.writeString(root.resolve("b.md"), "## Prerequisites\n- Real\n");
    var result = new BeginnerMaterialAnalyzer().analyze(root, "a.md", List.of("a.md", "b.md"), Set.of(), 10000);
    assertEquals(Set.of("Real"), result.chapters().get(1).knowledgeBefore());
    assertEquals(1, result.chapters().getFirst().explained().size());
  }
  @Test void excludedPagesAndInvalidUtf8RemainUnreviewed() throws Exception {
    Files.createDirectory(root.resolve("secrets"));
    Files.writeString(root.resolve("secrets/hidden.md"), "## Concepts\n- Hidden: secret\n");
    Files.write(root.resolve("a.md"), new byte[]{(byte)0xff});
    var result = new BeginnerMaterialAnalyzer().analyze(root, "a.md", List.of("secrets/hidden.md", "a.md"), Set.of(), 1000);
    assertTrue(result.chapters().stream().allMatch(c -> c.state() == BeginnerMaterialAnalyzer.ReadState.FAILED));
    assertTrue(result.chapters().getLast().knowledgeAfter().isEmpty());
  }
  @Test void inventoryDepthLimitCannotClaimCompleteCoverage() throws Exception {
    Files.writeString(root.resolve("a.md"), "# A\n");
    Path nested = root;
    for (int i = 0; i < 17; i++) nested = Files.createDirectory(nested.resolve("d"));
    Files.writeString(nested.resolve("hidden.md"), "# Hidden\n");
    var result = new BeginnerMaterialAnalyzer().analyze(root, "a.md", List.of("a.md"), Set.of(), 1000);
    assertFalse(result.complete());
    assertTrue(result.warnings().stream().anyMatch(w -> w.contains("depth")));
  }
  @Test void partialUnicodeKeepsOnlyCompleteLinesAndDoesNotTeachUnreadConcepts() throws Exception {
    Files.writeString(root.resolve("a.md"), "## Concepts\n- 概念: 説明\n- 未読: 説明\n");
    var result = new BeginnerMaterialAnalyzer().analyze(root, "a.md", List.of("a.md"), Set.of(), 22);
    assertEquals(BeginnerMaterialAnalyzer.ReadState.PARTIAL, result.chapters().getFirst().state());
    assertTrue(result.chapters().getFirst().knowledgeAfter().isEmpty());
    assertFalse(result.complete());
  }
}

