package dev.mikoto2000.rei.cli;

import static org.junit.jupiter.api.Assertions.*;

import dev.mikoto2000.rei.core.completion.DocumentPathResolver;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 文書パス対象の解決の結果と状態の契約を検証するテスト。 */
class DocumentPathResolverTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path directory;

  /** 軽量CLIでの対話で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void globExpansionUsesOnlyTheClientLocalDirectory() throws Exception {
    var docs = Files.createDirectory(directory.resolve("docs"));
    var text = Files.writeString(docs.resolve("a.txt"), "local");
    var markdown = Files.writeString(docs.resolve("b.md"), "local");
    assertEquals(
        List.of(text.toString(), markdown.toString()),
        DocumentPathResolver.resolve(List.of("./docs/*", "docs/*"), directory));
    assertEquals(
        List.of("docs/a.txt"), DocumentPathResolver.resolve(List.of("docs/a.txt"), directory));
    assertThrows(
        IllegalArgumentException.class,
        () -> DocumentPathResolver.resolve(List.of("missing/*.md"), directory));
  }
}
