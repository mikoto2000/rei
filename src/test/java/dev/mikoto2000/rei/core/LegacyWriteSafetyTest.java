package dev.mikoto2000.rei.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Legacy compatibility and overlapping-match regressions. */
class LegacyWriteSafetyTest {
  @TempDir Path root;

  @Test
  void overlappingOccurrencesAreAmbiguousAndNeverWritten() throws Exception {
    Path file = root.resolve("a.txt");
    Files.writeString(file, "aaaa");
    var result = new Tools().applyTextDiff(file.toString(), "aaa", "b", "UTF-8");
    assertFalse(result.success());
    assertEquals("oldText が複数箇所に一致しました", result.message());
    assertEquals("aaaa", Files.readString(file));
  }

  @Test
  void cp932AndUtf8BomPreserveBytesAndCrLfAndMissingTextContract() throws Exception {
    for (Charset charset : new Charset[] {Charset.forName("Windows-31J"), StandardCharsets.UTF_8}) {
      Path file = root.resolve(charset.name() + ".txt");
      String prefix = charset.equals(StandardCharsets.UTF_8) ? "\ufeff" : "";
      Files.writeString(file, prefix + "日本語\r\n古い値\r\n", charset);
      var tools = new Tools();
      assertTrue(tools.applyTextDiff(file.toString(), "古い値", "新しい値", charset.name()).success());
      assertEquals(prefix + "日本語\r\n新しい値\r\n", Files.readString(file, charset));
      byte[] expected = Files.readAllBytes(file);
      var missing = tools.applyTextDiff(file.toString(), "missing", "x", charset.name());
      assertFalse(missing.success());
      assertEquals("oldText が見つかりません", missing.message());
      org.junit.jupiter.api.Assertions.assertArrayEquals(expected, Files.readAllBytes(file));
    }
  }

  @Test
  void promptPrefersReviewedChangesWithoutArbitraryThousandCharacterSplitting() throws Exception {
    String prompt = Files.readString(Path.of("src/main/resources/system-prompt.md"));
    assertFalse(prompt.contains("差分が 1000 文字"));
    assertTrue(prompt.contains("proposeTextChangeSet"));
    assertTrue(prompt.contains("proposeTextDocumentChangeSet"));
  }
}
