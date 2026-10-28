package dev.mikoto2000.rei.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.storage.StorageDatabase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MarkdownSectionReadTest {
  @TempDir Path root;

  Tools.ReadFileRequest request(String json) throws Exception {
    return StorageDatabase.JSON.readValue(json, Tools.ReadFileRequest.class);
  }

  @Test
  void pathsDisambiguateHeadingsAndCommonmarkExcludesFencesAndFrontmatter() throws Exception {
    Files.writeString(
        root.resolve("design.md"),
        "---\ntitle: example\n# fake\n---\n# 設計\n## 認証\n### ログイン\n本文😀\n```md\n# 偽見出し\n```\n### ログアウト\n終わり\n## 永続化\n### ログイン\n別本文\n");
    var tools = new Tools();
    var ambiguous =
        tools
            .readMultiFile(List.of(request("{\"path\":\"design.md\",\"section\":\"ログイン\"}")), root)
            .getFirst();
    assertEquals(2, ambiguous.selection().candidates().size());
    var selected =
        tools
            .readMultiFile(
                List.of(
                    request("{\"path\":\"design.md\",\"sectionPath\":[\"設計\",\"認証\",\"ログイン\"]}")),
                root)
            .getFirst();
    assertEquals(7, selected.startLine());
    assertTrue(String.join("\n", selected.content()).contains("本文😀"));
    assertFalse(String.join("\n", selected.content()).contains("ログアウト"));
    assertNotNull(selected.version());
    assertEquals(List.of("設計", "認証", "ログイン"), selected.selection().selected().path());
    assertEquals("設計/認証/ログイン", selected.evidence().selector());
    var fake =
        tools
            .readMultiFile(List.of(request("{\"path\":\"design.md\",\"section\":\"偽見出し\"}")), root)
            .getFirst();
    assertEquals("SECTION_NOT_FOUND", fake.problem().code());
  }

  @Test
  void setextSkippedLevelsChildrenCrLfAndContinuationRemainVersionBound() throws Exception {
    Files.writeString(
        root.resolve("design.md"),
        "設計\r\n====\r\n本文\r\n#### 子😀\r\n" + "長い本文\r\n".repeat(1200) + "# 次\r\n");
    var tools = new Tools();
    var noChildren =
        tools
            .readMultiFile(
                List.of(
                    request(
                        "{\"path\":\"design.md\",\"section\":\"設計\",\"includeChildren\":false}")),
                root)
            .getFirst();
    assertEquals(List.of("設計", "====", "本文"), noChildren.content());
    var first =
        tools
            .readMultiFile(
                List.of(
                    request(
                        "{\"path\":\"design.md\",\"sectionPath\":[\"設計\",\"子😀\"],\"headingLevel\":4}")),
                root)
            .getFirst();
    assertTrue(first.truncated());
    assertNotNull(first.nextRead());
    var next = tools.readMultiFile(List.of(first.nextRead()), root).getFirst();
    assertNotNull(next.selection().selected());
    assertEquals(first.version(), next.version());
    Files.writeString(root.resolve("design.md"), "# changed");
    assertEquals(
        "FILE_CHANGED",
        tools.readMultiFile(List.of(first.nextRead()), root).getFirst().problem().code());
  }
}
