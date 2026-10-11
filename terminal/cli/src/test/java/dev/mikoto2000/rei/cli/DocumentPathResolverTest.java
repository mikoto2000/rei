package dev.mikoto2000.rei.cli;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import dev.mikoto2000.rei.core.completion.DocumentPathResolver;
class DocumentPathResolverTest {
  @TempDir Path directory;
  @Test void globExpansionUsesOnlyTheClientLocalDirectory()throws Exception {
    var docs=Files.createDirectory(directory.resolve("docs"));var text=Files.writeString(docs.resolve("a.txt"),"local");var markdown=Files.writeString(docs.resolve("b.md"),"local");
    assertEquals(List.of(text.toString(),markdown.toString()),DocumentPathResolver.resolve(List.of("./docs/*","docs/*"),directory));
    assertEquals(List.of("docs/a.txt"),DocumentPathResolver.resolve(List.of("docs/a.txt"),directory));
    assertThrows(IllegalArgumentException.class,()->DocumentPathResolver.resolve(List.of("missing/*.md"),directory));
  }
}
