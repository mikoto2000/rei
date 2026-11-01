package dev.mikoto2000.rei.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** An independent patch consumer validates generated hunks, ranges and newline markers. */
@org.junit.jupiter.api.Tag("integration")
class TextDiffContractTest {
  @TempDir Path root;

  @Test
  void gitAppliesRandomizedDiffsAndFallbackExactly() throws Exception {
    var random = new Random(2048);
    for (int iteration = 0; iteration < 40; iteration++) {
      String separator = iteration % 2 == 0 ? "\n" : "\r\n";
      var old = new StringBuilder();
      var next = new StringBuilder();
      for (int i = 0; i < 60; i++) {
        String line = "repeated " + random.nextInt(8) + separator;
        old.append(line);
        if (random.nextInt(5) != 0) next.append(line);
        if (random.nextInt(6) == 0) next.append("inserted " + i + separator);
      }
      if (iteration % 3 == 0) next.append("no final newline");
      check(old.toString(), next.toString());
    }
    check("", "created\n");
    check("deleted\n", "");
    check("old", "new");
    check("old\n".repeat(400), "new\n".repeat(400));
  }

  private void check(String before, String after) throws Exception {
    Files.writeString(root.resolve("a.txt"), before);
    var change = new TextDocumentTransaction.Change("a.txt", before, after);
    var page = TextDiff.page(List.of(change), 0, 65536);
    var process =
        new ProcessBuilder("git", "apply", "--no-index", "--whitespace=nowarn", "-p0", "-")
            .directory(root.toFile())
            .redirectErrorStream(true)
            .start();
    try (var input = process.getOutputStream()) {
      input.write(page.diff().getBytes(StandardCharsets.UTF_8));
    }
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertEquals(0, process.waitFor(), output + "\n" + page.diff());
    assertEquals(after, Files.readString(root.resolve("a.txt")));
  }
}
