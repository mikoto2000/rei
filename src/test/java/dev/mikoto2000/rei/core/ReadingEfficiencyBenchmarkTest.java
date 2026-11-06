package dev.mikoto2000.rei.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import dev.mikoto2000.rei.core.contextbudget.TokenEstimator;
import dev.mikoto2000.rei.core.contextbudget.ToolResultDeliveryProperties;
import dev.mikoto2000.rei.storage.ReadingBenchmarkDatabase;
import dev.mikoto2000.rei.storage.StorageDatabase;
import dev.mikoto2000.rei.storage.StorageMigrationCoordinator;
import dev.mikoto2000.rei.tooldelivery.ReadingMetrics;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/** Deterministic work/payload comparisons, without wall-clock pass thresholds or model calls. */
class ReadingEfficiencyBenchmarkTest {
  @TempDir Path root;

  Tools tools(String path) {
    return new Tools() {
      @Override
      List<String> listFile(String base, Path directory) {
        return List.of(path);
      }
    };
  }

  Tools.GrepQuery query(String pattern, String base) {
    return new Tools.GrepQuery(
        pattern, base, false, true, false, false, 0, 0, null, true, null, null);
  }

  Map<String, Integer> payload(Object value) throws Exception {
    String json =
        value instanceof String text ? text : StorageDatabase.JSON.writeValueAsString(value);
    return Map.of(
        "bytes",
        json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
        "conservativeTokens",
        TokenEstimator.conservative().text(json),
        "tokenizerAwareTokens",
        TokenEstimator.tokenizerAware().text(json));
  }

  void report(String name, Object value) throws Exception {
    Path reports = Path.of("target/reading-benchmark");
    Files.createDirectories(reports);
    Files.writeString(
        reports.resolve(name + ".json"),
        StorageDatabase.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value));
  }

  @Test
  void repeatedLineMatchesHaveOneCanonicalSourceOnTheWire() throws Exception {
    Files.writeString(
        root.resolve("fixture.txt"), "needle 日本語\n" + "unrelated\n".repeat(100) + "needle end\n");
    var queries =
        java.util.stream.IntStream.range(0, 8).mapToObj(i -> query("needle", ".")).toList();
    var legacy =
        tools("fixture.txt").searchAndRead(new Tools.SearchAndReadRequest(queries, 0, 1), root);
    var compact =
        tools("fixture.txt")
            .searchAndRead(
                new Tools.SearchAndReadRequest(queries, 0, 1, null, null, null, null, true), root);
    var before = payload(new SearchReadResultConverter().convert(legacy, List.class));
    var after = payload(new SearchReadResultConverter().convert(compact, List.class));
    long duplicateBefore;
    long duplicateAfter;
    var beforeRun = new AgentRunContext("duplicate-before", "chat", root);
    try (var scope = AgentRunScope.open(beforeRun)) {
      new SearchReadResultConverter().convert(legacy, List.class);
      duplicateBefore =
          ReadingMetrics.snapshot(beforeRun).getOrDefault("wireDuplicateBodyBytes", 0L);
    }
    var afterRun = new AgentRunContext("duplicate-after", "chat", root);
    try (var scope = AgentRunScope.open(afterRun)) {
      new SearchReadResultConverter().convert(compact, List.class);
      duplicateAfter = ReadingMetrics.snapshot(afterRun).getOrDefault("wireDuplicateBodyBytes", 0L);
    }
    assertEquals(208, duplicateBefore);
    assertEquals(0, duplicateAfter);
    assertEquals(legacy.getFirst().sections(), compact.getFirst().sections());
    assertTrue(after.get("tokenizerAwareTokens") < before.get("tokenizerAwareTokens"));
    report(
        "repeated-line",
        Map.of(
            "before",
            before,
            "after",
            after,
            "toolCallsBefore",
            1,
            "toolCallsAfter",
            1,
            "duplicateBodyBytesBefore",
            duplicateBefore,
            "duplicateBodyBytesAfter",
            duplicateAfter,
            "modelCalls",
            0));
  }

  @Test
  void distinctQueriesShareTheSameSourcePass() throws Exception {
    Files.writeString(
        root.resolve("queries.txt"),
        "needle0 needle1 needle2 needle3 needle4 needle5 needle6 needle7\n");
    var queries =
        java.util.stream.IntStream.range(0, 8).mapToObj(i -> query("needle" + i, ".")).toList();
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
  @EnabledIfSystemProperty(named = "rei.benchmark.search-initial", matches = "true")
  void searchInitialMeasuresTimeToFirstPageByCandidateCount() throws Exception {
    int warmups = 1;
    int repetitions = 3;
    var sizes = List.of(10, 100, 1_000, 5_000);
    var paths = new ArrayList<String>();
    Files.createDirectory(root.resolve("sources"));
    for (int i = 0; i < sizes.getLast(); i++) {
      String path = String.format(Locale.ROOT, "sources/%05d.txt", i);
      Files.writeString(root.resolve(path), i == 0 ? "hit\nhit\n" : "miss\n");
      paths.add(path);
    }
    var database = ReadingBenchmarkDatabase.prepare(root.resolve("data"));
    var cases = new ArrayList<Map<String, Object>>();
    try (var lease = ReadingBenchmarkDatabase.keepOpen(database)) {
      for (int size : sizes) {
        var candidates = List.copyOf(paths.subList(0, size));
        var reader =
            new Tools() {
              @Override
              List<String> gitLsFiles(List<String> pathSpecs, Path directory) {
                return candidates;
              }
            };
        var search = new PagedFileSearch(reader, ReadingTestStores.store(database));
        var request = searchResumeRequest(query("hit", "sources"), null);
        var samples = new ArrayList<Long>();
        for (int i = 0; i < warmups + repetitions; i++) {
          var owner = new AgentRunContext("search-initial-" + size + "-" + i, "chat", root);
          try (var scope = AgentRunScope.open(owner)) {
            long started = System.nanoTime();
            var page = search.read(request, root);
            long elapsed = System.nanoTime() - started;
            assertNull(page.error());
            assertEquals(size, page.progress().total());
            assertTrue(page.resultCursor() != null && page.resultCursor().startsWith("RESULT:"));
            assertEquals(candidates.getFirst(), page.files().getFirst().path());
            if (i >= warmups) samples.add(elapsed);
          }
        }
        cases.add(Map.of("candidates", size, "totalNanos", samples));
        System.out.println("Initial search benchmark completed " + size + " candidates");
      }
    }
    report(
        "search-initial",
        Map.of("warmups", warmups, "samplesPerSize", repetitions, "scanLimit", 1, "cases", cases));
  }

  @Test
  @EnabledIfSystemProperty(named = "rei.benchmark.search-resume", matches = "true")
  void searchResumeMeasuresInventoryVerificationByCursor() throws Exception {
    int warmups = 1;
    int repetitions = 3;
    var sizes = new ArrayList<>(List.of(10, 100, 1_000, 5_000));
    if (Boolean.getBoolean("rei.benchmark.search-resume.include-max")) sizes.add(20_000);
    var paths = new ArrayList<String>();
    Files.createDirectory(root.resolve("sources"));
    for (int i = 0; i < sizes.getLast(); i++) {
      String path = String.format(Locale.ROOT, "sources/%05d.txt", i);
      Files.writeString(root.resolve(path), i == 0 ? "hit\nhit\nhit\n" : "miss\n");
      paths.add(path);
    }
    var cases = new ArrayList<Map<String, Object>>();
    var database = ReadingBenchmarkDatabase.prepare(root.resolve("data"));
    try (var lease = ReadingBenchmarkDatabase.keepOpen(database)) {
      for (int size : sizes) {
        var candidates = List.copyOf(paths.subList(0, size));
        var reader =
            new Tools() {
              @Override
              List<String> gitLsFiles(List<String> pathSpecs, Path directory) {
                return candidates;
              }
            };
        var store = ReadingTestStores.store(database);
        var degrees = List.of(1, 2, 4);
        var searches =
            degrees.stream().map(degree -> new PagedFileSearch(reader, store, degree)).toList();
        var owner = new AgentRunContext("search-resume-" + size, "chat", root);
        var samples = new LinkedHashMap<Integer, Map<String, List<Map<String, Long>>>>();
        for (int degree : degrees)
          samples.put(degree, Map.of("RESULT", new ArrayList<>(), "SCAN", new ArrayList<>()));
        try (var scope = AgentRunScope.open(owner)) {
          var result =
              searches.getFirst().read(searchResumeRequest(query("hit", "sources"), null), root);
          var scan =
              searches.getFirst().read(searchResumeRequest(query("absent", "sources"), null), root);
          assertNull(result.error());
          assertNull(scan.error());
          assertEquals(size, result.progress().total());
          assertEquals(size, scan.progress().total());
          assertTrue(result.resultCursor() != null && result.resultCursor().startsWith("RESULT:"));
          assertTrue(scan.scanCursor() != null && scan.scanCursor().startsWith("SCAN:"));
          var kinds = List.of("RESULT", "SCAN");
          var cursors = List.of(result.resultCursor(), scan.scanCursor());
          for (int iteration = 0; iteration < warmups + repetitions; iteration++) {
            for (int turn = 0; turn < degrees.size(); turn++) {
              int degreeIndex = (iteration + turn) % degrees.size();
              for (int cursorIndex = 0; cursorIndex < kinds.size(); cursorIndex++) {
                int variant = (iteration + cursorIndex) % kinds.size();
                var sample =
                    searchResumeSample(
                        searches.get(degreeIndex), owner, cursors.get(variant), size);
                if (iteration >= warmups)
                  samples.get(degrees.get(degreeIndex)).get(kinds.get(variant)).add(sample);
              }
            }
          }
        }
        cases.add(Map.of("candidates", size, "samples", samples));
        System.out.println("Search resume benchmark completed " + size + " candidates");
      }
    }
    report(
        "search-resume-parallel",
        Map.of(
            "warmupsPerCursor", warmups,
            "samplesPerCursor", repetitions,
            "scanLimit", 1,
            "maxLines", 1,
            "cases", cases));
  }

  private static PagedFileSearch.Request searchResumeRequest(Tools.GrepQuery query, String cursor) {
    return new PagedFileSearch.Request(
        query == null ? null : List.of(query), 0, 1024, 4096, 1, 1, cursor);
  }

  private Map<String, Long> searchResumeSample(
      PagedFileSearch search, AgentRunContext owner, String cursor, int size) {
    var request = searchResumeRequest(null, cursor);
    var before = ReadingMetrics.snapshot(owner);
    long started = System.nanoTime();
    var page = search.read(request, root);
    long total = System.nanoTime() - started;
    var after = ReadingMetrics.snapshot(owner);
    assertNull(page.error());
    assertEquals(size, page.progress().total());
    assertEquals(size, metricDelta(before, after, "resumeVerifiedCandidates"));
    long verification = metricDelta(before, after, "resumeInventoryVerificationNanos");
    assertTrue(verification > 0 && verification <= total);
    long scanned = metricDelta(before, after, "scannedFiles");
    if (cursor.startsWith("RESULT:")) {
      assertEquals(0, scanned);
      assertEquals(2, page.files().getFirst().sections().getFirst().startLine());
    } else {
      assertEquals(1, scanned);
      assertTrue(page.files().isEmpty());
      assertEquals("FILE_ENUMERATION_LIMIT", page.progress().stopReason());
    }
    return Map.of("totalNanos", total, "verificationNanos", verification, "scannedFiles", scanned);
  }

  private static long metricDelta(Map<String, Long> before, Map<String, Long> after, String name) {
    return after.getOrDefault(name, 0L) - before.getOrDefault(name, 0L);
  }

  @Test
  void nextReadMeasuresRepeatedSourceWork() throws Exception {
    String name = "continuation.txt";
    var source =
        java.util.stream.IntStream.rangeClosed(1, 8000)
            .mapToObj(i -> i + " UTF-8 fixture line with Japanese text 日本語")
            .toList();
    Path file = root.resolve(name);
    Files.writeString(file, String.join("\n", source) + "\n");
    var withoutCache =
        new Tools() {
          @Override
          List<String> verifiedReadLines(
              Path root,
              ReadFileRequest request,
              FileSnapshots snapshots,
              FileSnapshots.Snapshot snapshot)
              throws java.io.IOException {
            return snapshots.lines(
                snapshot, request.charset(), () -> snapshotLines(snapshot, request.charset()));
          }
        };
    var withCache = new Tools();
    var readers = List.of(withoutCache, withCache);
    var properties = new ToolResultDeliveryProperties();
    properties.setPageMaxTokens(512);
    properties.setPageTargetTokens(512);
    for (var reader : readers) reader.configureDelivery(TokenEstimator.conservative(), properties);
    int warmups = 3;
    int repetitions = 7;
    int requestedLines = 320;
    var expected = source.subList(0, requestedLines);
    var samples = List.of(new ArrayList<Map<String, Long>>(), new ArrayList<Map<String, Long>>());
    for (int iteration = 0; iteration < warmups + repetitions; iteration++) {
      for (int turn = 0; turn < readers.size(); turn++) {
        int variant = (iteration + turn) % readers.size();
        var owner = new AgentRunContext("next-read-" + iteration + "-" + variant, "chat", root);
        var sample = nextReadSample(readers.get(variant), owner, name, expected, variant == 1);
        if (iteration >= warmups) samples.get(variant).add(sample);
      }
    }
    assertEquals(
        samples.getFirst().stream().map(sample -> sample.get("toolCalls")).toList(),
        samples.getLast().stream().map(sample -> sample.get("toolCalls")).toList());
    report(
        "next-read",
        Map.of(
            "fileBytes",
            Files.size(file),
            "fileLines",
            source.size(),
            "requestedLines",
            requestedLines,
            "pageTargetTokens",
            512,
            "warmupChains",
            warmups,
            "modelCalls",
            0,
            "beforeSamples",
            samples.getFirst(),
            "samples",
            samples.getLast()));
  }

  private Map<String, Long> nextReadSample(
      Tools reader, AgentRunContext owner, String name, List<String> expected, boolean cached)
      throws java.io.IOException {
    var pages = new ArrayList<Tools.ReadFileResult>();
    var next = new Tools.ReadFileRequest(name, 1, expected.size());
    long elapsed;
    try (var scope = AgentRunScope.open(owner)) {
      long started = System.nanoTime();
      while (next != null && pages.size() < expected.size()) {
        var page = reader.readMultiFile(List.of(next), root).getFirst();
        pages.add(page);
        next = page.nextRead();
      }
      elapsed = System.nanoTime() - started;
    }
    assertNull(next);
    var returned = new ArrayList<String>();
    var line = new StringBuilder();
    for (var page : pages) {
      assertNull(page.error());
      for (int i = 0; i < page.content().size(); i++) {
        line.append(page.content().get(i));
        if (i == page.content().size() - 1
            && page.nextRead() != null
            && page.nextRead().offset() > 0) break;
        returned.add(line.toString());
        line.setLength(0);
      }
    }
    assertEquals(expected, returned);
    long calls = pages.size();
    assertTrue(calls > 1);
    var metrics = ReadingMetrics.snapshot(owner);
    long hits = metrics.getOrDefault("continuationLineCacheHits", 0L);
    assertEquals(calls, metrics.get("physicalReads"));
    assertEquals(calls - 1, metrics.get("continuations"));
    assertEquals(cached ? calls - 1 : 0, hits);
    assertEquals(calls - hits, metrics.get("decodes"));
    assertEquals(calls - hits, metrics.get("lineSplits"));
    return Map.of(
        "chainNanos", elapsed,
        "toolCalls", calls,
        "physicalReads", metrics.get("physicalReads"),
        "decodes", metrics.get("decodes"),
        "lineSplits", metrics.get("lineSplits"),
        "continuationLineCacheHits", hits,
        "sourceReadNanos", metrics.get("sourceReadNanos"),
        "sourceHashNanos", metrics.get("sourceHashNanos"),
        "sourceDecodeNanos", metrics.get("sourceDecodeNanos"),
        "sourceLineSplitNanos", metrics.get("sourceLineSplitNanos"));
  }

  @Test
  void sectionReadReturnsLessSourceThanTheSameBudgetFullDocumentRead() throws Exception {
    String source =
        "# root\n"
            + java.util.stream.IntStream.range(0, 200)
                .mapToObj(i -> "## section" + i + "\n本文 日本語 " + i + "\n")
                .collect(java.util.stream.Collectors.joining());
    Files.writeString(root.resolve("review.md"), source);
    var reader = new Tools();
    var before =
        reader.readMultiFile(List.of(new Tools.ReadFileRequest("review.md", null, null)), root);
    var request =
        StorageDatabase.JSON.readValue(
            "{\"path\":\"review.md\",\"sectionPath\":[\"root\",\"section100\"]}",
            Tools.ReadFileRequest.class);
    var after = reader.readMultiFile(List.of(request), root);
    assertTrue(after.getFirst().content().contains("本文 日本語 100"));
    assertTrue(
        payload(after).get("tokenizerAwareTokens") < payload(before).get("tokenizerAwareTokens"));
    report(
        "markdown",
        Map.of(
            "before",
            payload(before),
            "after",
            payload(after),
            "toolCallsBefore",
            1,
            "toolCallsAfter",
            1,
            "modelCalls",
            0));
  }

  @Test
  @org.junit.jupiter.api.Tag("integration")
  void resultCursorAvoidsNativeReadOfTheGapWithIdenticalTokenBudgets() throws Exception {
    Files.writeString(root.resolve("gap.txt"), "hit\n" + "irrelevant\n".repeat(998) + "hit\n");
    var reader = tools("gap.txt");
    var properties = new ToolResultDeliveryProperties();
    properties.setPageMaxTokens(512);
    properties.setPageTargetTokens(512);
    reader.configureDelivery(TokenEstimator.conservative(), properties);
    Path data = root.resolve("data");
    try (var gate = new StorageMigrationCoordinator(data)) {
      gate.prepare();
    }
    var query = query("hit", "gap.txt");
    try (var scope =
        AgentRunScope.open(new AgentRunContext("gap-benchmark", "chat", root, "project"))) {
      var initial =
          reader
              .searchAndRead(
                  new Tools.SearchAndReadRequest(List.of(query), 1, 1, 4096, 512, 1, null), root)
              .getFirst();
      int beforeCalls = 1;
      int beforeTokens = payload(List.of(initial)).get("conservativeTokens");
      var next = initial.nextRead();
      while (next != null && beforeCalls < 100) {
        var page = reader.readMultiFile(List.of(next), root);
        beforeCalls++;
        beforeTokens += payload(page).get("conservativeTokens");
        next = page.getFirst().nextRead();
      }
      var search = new PagedFileSearch(reader, ReadingTestStores.store(new StorageDatabase(data)));
      var first =
          search.read(
              new PagedFileSearch.Request(List.of(query), 1, 512, 4096, 128, 1, null), root);
      var second =
          search.read(
              new PagedFileSearch.Request(null, null, 512, 4096, 128, 1000, first.resultCursor()),
              root);
      assertEquals(null, second.error());
      assertEquals(1000, second.files().getFirst().sections().getLast().endLine());
      assertTrue(beforeCalls > 2);
      int afterTokens =
          payload(first).get("conservativeTokens") + payload(second).get("conservativeTokens");
      assertTrue(afterTokens < beforeTokens);
      report(
          "distant-hits",
          Map.of(
              "toolCallsBefore",
              beforeCalls,
              "toolCallsAfter",
              2,
              "returnedEstimatedTokensBefore",
              beforeTokens,
              "returnedEstimatedTokensAfter",
              afterTokens,
              "continuationsBefore",
              beforeCalls - 1,
              "continuationsAfter",
              1,
              "modelCalls",
              0));
    }
  }
}
