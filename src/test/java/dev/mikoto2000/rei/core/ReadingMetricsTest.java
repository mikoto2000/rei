package dev.mikoto2000.rei.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReadingMetricsTest {
  @TempDir Path root;

  @Test
  void generatedAndDeliveredAreSeparateAndRunsAreIsolated() throws Exception {
    Files.writeString(root.resolve("a.txt"), "one\ntwo\n");
    var owner = new AgentRunContext("metrics-one", "chat", root);
    try (var scope = AgentRunScope.open(owner)) {
      new Tools().readMultiFile(List.of(new Tools.ReadFileRequest("a.txt", 1, 2)), root);
      var values = ReadingMetrics.snapshot(owner);
      assertEquals(1, values.getOrDefault("physicalReads", 0L));
      assertEquals(1, values.getOrDefault("decodes", 0L));
      assertEquals(1, values.getOrDefault("lineSplits", 0L));
      assertEquals(1, values.getOrDefault("toolCalls", 0L));
      assertEquals(0, values.getOrDefault("deliveredEstimatedTokens", 0L));
    }
    assertEquals(0, ReadingMetrics.snapshot(new AgentRunContext("other", "chat", root)).size());
  }

  @Test
  void repeatableSearchBaseline() throws Exception {
    Files.writeString(root.resolve("fixture.txt"), "needle 日本語\n" + "unrelated\n".repeat(100) + "needle end\n");
    var queries = java.util.stream.IntStream.range(0, 8)
        .mapToObj(i -> new Tools.GrepQuery("needle", ".", false, true, false, false, 0, 0, null, true, null, null)).toList();
    var tools = new Tools() {
      @Override List<String> listFile(String base, Path directory) { return List.of("fixture.txt"); }
    };
    var owner = new AgentRunContext("metrics-baseline", "chat", root);
    try (var scope = AgentRunScope.open(owner)) {
      var result = tools.searchAndRead(new Tools.SearchAndReadRequest(queries, 0, 1), root);
      assertEquals(2, result.getFirst().sections().size());
      assertEquals(16, result.getFirst().matches().size());
      var values = ReadingMetrics.snapshot(owner);
      assertEquals(1, values.get("physicalReads"));
      assertEquals(1, values.get("decodes"));
      assertEquals(1, values.get("lineSplits"));
      Files.createDirectories(Path.of("target/reading-benchmark"));
      Files.writeString(Path.of("target/reading-benchmark/search.json"),
          dev.mikoto2000.rei.storage.StorageDatabase.JSON.writeValueAsString(values));
    }
  }
}
