package dev.mikoto2000.rei.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import dev.mikoto2000.rei.core.contextbudget.TokenEstimator;
import dev.mikoto2000.rei.core.contextbudget.ToolResultDeliveryProperties;
import dev.mikoto2000.rei.storage.StorageDatabase;
import dev.mikoto2000.rei.storage.StorageMigrationCoordinator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deterministic work/payload comparisons, without wall-clock pass thresholds or model calls. */
class ReadingEfficiencyBenchmarkTest {
  @TempDir Path root;
  Tools tools(String path) {
    return new Tools() { @Override List<String> listFile(String base, Path directory) { return List.of(path); } };
  }
  Tools.GrepQuery query(String pattern, String base) {
    return new Tools.GrepQuery(pattern, base, false, true, false, false, 0, 0, null, true, null, null);
  }
  Map<String, Integer> payload(Object value) throws Exception {
    String json = value instanceof String text ? text : StorageDatabase.JSON.writeValueAsString(value);
    return Map.of("bytes", json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
        "conservativeTokens", TokenEstimator.conservative().text(json),
        "tokenizerAwareTokens", TokenEstimator.tokenizerAware().text(json));
  }
  void report(String name, Object value) throws Exception {
    Path reports = Path.of("target/reading-benchmark");
    Files.createDirectories(reports);
    Files.writeString(reports.resolve(name + ".json"), StorageDatabase.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value));
  }

  @Test
  void repeatedLineMatchesHaveOneCanonicalSourceOnTheWire() throws Exception {
    Files.writeString(root.resolve("fixture.txt"), "needle 日本語\n" + "unrelated\n".repeat(100) + "needle end\n");
    var queries = java.util.stream.IntStream.range(0, 8).mapToObj(i -> query("needle", ".")).toList();
    var legacy = tools("fixture.txt").searchAndRead(new Tools.SearchAndReadRequest(queries, 0, 1), root);
    var compact = tools("fixture.txt").searchAndRead(new Tools.SearchAndReadRequest(queries, 0, 1, null, null, null, null, true), root);
    var before = payload(new SearchReadResultConverter().convert(legacy, List.class));
    var after = payload(new SearchReadResultConverter().convert(compact, List.class));
    assertEquals(legacy.getFirst().sections(), compact.getFirst().sections());
    assertTrue(after.get("tokenizerAwareTokens") < before.get("tokenizerAwareTokens"));
    report("repeated-line", Map.of("before", before, "after", after, "toolCallsBefore", 1, "toolCallsAfter", 1, "modelCalls", 0));
  }

  @Test
  void distinctQueriesShareTheSameSourcePass() throws Exception {
    Files.writeString(root.resolve("queries.txt"), "needle0 needle1 needle2 needle3 needle4 needle5 needle6 needle7\n");
    var queries = java.util.stream.IntStream.range(0, 8).mapToObj(i -> query("needle" + i, ".")).toList();
    var owner = new AgentRunContext("distinct-query-benchmark", "chat", root);
    try (var scope = AgentRunScope.open(owner)) {
      var result = tools("queries.txt").grepMultiQuery(queries, root);
      assertEquals(8, result.size());
      assertTrue(result.stream().allMatch(r -> r.matches().size() == 1));
      assertEquals(1L, ReadingMetrics.snapshot(owner).get("decodes"));
      assertEquals(1L, ReadingMetrics.snapshot(owner).get("scannedFiles"));
      report("distinct-queries", ReadingMetrics.snapshot(owner));
    }
  }

  @Test
  void sectionReadReturnsLessSourceThanTheSameBudgetFullDocumentRead() throws Exception {
    String source = "# root\n" + java.util.stream.IntStream.range(0, 200)
        .mapToObj(i -> "## section" + i + "\n本文 日本語 " + i + "\n").collect(java.util.stream.Collectors.joining());
    Files.writeString(root.resolve("review.md"), source);
    var reader = new Tools();
    var before = reader.readMultiFile(List.of(new Tools.ReadFileRequest("review.md", null, null)), root);
    var request = StorageDatabase.JSON.readValue("{\"path\":\"review.md\",\"sectionPath\":[\"root\",\"section100\"]}", Tools.ReadFileRequest.class);
    var after = reader.readMultiFile(List.of(request), root);
    assertTrue(after.getFirst().content().contains("本文 日本語 100"));
    assertTrue(payload(after).get("tokenizerAwareTokens") < payload(before).get("tokenizerAwareTokens"));
    report("markdown", Map.of("before", payload(before), "after", payload(after), "toolCallsBefore", 1, "toolCallsAfter", 1, "modelCalls", 0));
  }

  @Test
  void resultCursorAvoidsNativeReadOfTheGapWithIdenticalTokenBudgets() throws Exception {
    Files.writeString(root.resolve("gap.txt"), "hit\n" + "irrelevant\n".repeat(998) + "hit\n");
    var reader = tools("gap.txt");
    var properties = new ToolResultDeliveryProperties();
    properties.setPageMaxTokens(512); properties.setPageTargetTokens(512);
    reader.configureDelivery(TokenEstimator.conservative(), properties);
    Path data = root.resolve("data");
    try (var gate = new StorageMigrationCoordinator(data)) { gate.prepare(); }
    var query = query("hit", "gap.txt");
    try (var scope = AgentRunScope.open(new AgentRunContext("gap-benchmark", "chat", root, "project"))) {
      var initial = reader.searchAndRead(new Tools.SearchAndReadRequest(List.of(query), 1, 1, 4096, 512, 1, null), root).getFirst();
      int beforeCalls = 1;
      int beforeTokens = payload(List.of(initial)).get("conservativeTokens");
      var next = initial.nextRead();
      while (next != null && beforeCalls < 100) {
        var page = reader.readMultiFile(List.of(next), root);
        beforeCalls++; beforeTokens += payload(page).get("conservativeTokens"); next = page.getFirst().nextRead();
      }
      var search = new PagedFileSearch(reader, new ReadingStateStore(new StorageDatabase(data)));
      var first = search.read(new PagedFileSearch.Request(List.of(query), 1, 512, 4096, 128, 1, null), root);
      var second = search.read(new PagedFileSearch.Request(null, null, 512, 4096, 128, 1000, first.resultCursor()), root);
      assertEquals(null, second.error());
      assertEquals(1000, second.files().getFirst().sections().getLast().endLine());
      assertTrue(beforeCalls > 2);
      int afterTokens = payload(first).get("conservativeTokens") + payload(second).get("conservativeTokens");
      assertTrue(afterTokens < beforeTokens);
      report("distant-hits", Map.of("toolCallsBefore", beforeCalls, "toolCallsAfter", 2,
          "returnedEstimatedTokensBefore", beforeTokens, "returnedEstimatedTokensAfter", afterTokens,
          "continuationsBefore", beforeCalls - 1, "continuationsAfter", 1, "modelCalls", 0));
    }
  }
}
