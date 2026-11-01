package dev.mikoto2000.rei.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import dev.mikoto2000.rei.storage.StorageDatabase;
import dev.mikoto2000.rei.storage.StorageMigrationCoordinator;
import dev.mikoto2000.rei.tooldelivery.ReadingProblem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReadingSecurityTest {
  @TempDir Path root;

  @Test
  void backtrackingWorkIsBoundedButJavaRegexFeaturesRemainSupported() {
    assertThrows(
        ReadingProblem.Failure.class,
        () ->
            BoundedRegex.find(
                java.util.regex.Pattern.compile("(a+)+$"), "a".repeat(2000) + "!", 1000));
    assertTrue(
        BoundedRegex.find(java.util.regex.Pattern.compile("(?<=prefix)(x)\\1"), "prefixxx", 1000));
  }

  @Test
  void pagedSearchCanBeExplicitlyRequestedByAReadOnlySubagent() {
    var policy =
        new dev.mikoto2000.rei.subagent.SubAgentToolPolicy(java.util.Set.of("searchFilePage"));
    policy.validate(List.of("searchFilePage"));
    assertEquals(List.of("searchFilePage"), policy.effectiveTools(List.of("searchFilePage")));
  }

  @Test
  void nativeReadErrorsHaveStructuredCategories() throws Exception {
    var result =
        new Tools()
            .readMultiFile(List.of(new Tools.ReadFileRequest("missing.txt", null, null)), root)
            .getFirst();
    assertEquals("FILE_NOT_FOUND", result.problem().code());
  }

  @Test
  void scanCoverageAdvancesWithoutRewardingReplaysOrRegressionsAcrossRestart() throws Exception {
    var evaluator = new dev.mikoto2000.rei.core.stagnation.ProgressEvaluator(root);
    String id = "a".repeat(64);
    java.util.function.BiFunction<Integer, String, String> page =
        (scanned, cursor) ->
            "{\"files\":[],\"scanCursor\":\""
                + cursor
                + "\",\"progress\":{\"scanned\":"
                + scanned
                + ",\"unscanned\":"
                + (256 - scanned)
                + ",\"total\":256,\"scanId\":\""
                + id
                + "\",\"stopReason\":\"FILE_ENUMERATION_LIMIT\"}}";
    var first =
        evaluator.afterTool(
            "searchFilePage",
            "{}",
            page.apply(128, "one"),
            evaluator.beforeTool("searchFilePage", "{}"));
    assertTrue(
        first.stream()
            .anyMatch(
                e ->
                    e.category()
                        == dev.mikoto2000.rei.core.stagnation.ProgressEvidence.Category
                            .COVERAGE_ADVANCED));
    var saved =
        StorageDatabase.JSON.readValue(
            StorageDatabase.JSON.writeValueAsString(evaluator.snapshot()),
            dev.mikoto2000.rei.core.stagnation.ProgressEvaluator.State.class);
    var restarted = new dev.mikoto2000.rei.core.stagnation.ProgressEvaluator(root);
    restarted.restore(saved);
    var legacyState =
        (com.fasterxml.jackson.databind.node.ObjectNode) StorageDatabase.JSON.valueToTree(saved);
    legacyState.remove("scanCoverage");
    assertTrue(
        StorageDatabase.JSON
            .treeToValue(
                legacyState, dev.mikoto2000.rei.core.stagnation.ProgressEvaluator.State.class)
            .scanCoverage()
            .isEmpty());
    for (int scanned : List.of(128, 64))
      assertTrue(
          restarted
              .afterTool(
                  "searchFilePage",
                  "{}",
                  page.apply(scanned, "changed"),
                  restarted.beforeTool("searchFilePage", "{}"))
              .isEmpty());
    assertTrue(
        restarted
            .afterTool(
                "searchFilePage",
                "{}",
                page.apply(256, "last"),
                restarted.beforeTool("searchFilePage", "{}"))
            .stream()
            .anyMatch(e -> e.acquisitionProgress()));
  }

  @Test
  @org.junit.jupiter.api.Tag("integration")
  void aValidCursorCannotCrossRunsAndExpirationIsDistinct() throws Exception {
    Files.writeString(root.resolve("a.txt"), "hit\nno\nhit\n");
    Path data = root.resolve("data");
    try (var gate = new StorageMigrationCoordinator(data)) {
      gate.prepare();
    }
    var now = Instant.parse("2026-10-28T00:00:00Z");
    var store =
        ReadingTestStores.store(new StorageDatabase(data), Clock.fixed(now, ZoneOffset.UTC));
    var owner = new AgentRunContext("secure", "chat", root, "project");
    String cursor;
    try (var scope = AgentRunScope.open(owner)) {
      var query =
          new Tools.GrepQuery(
              "hit", "a.txt", false, true, false, false, 0, 0, null, true, null, null);
      cursor =
          new PagedFileSearch(new Tools(), store)
              .read(new PagedFileSearch.Request(List.of(query), 0, 512, 2048, 1, 1, null), root)
              .resultCursor();
    }
    try (var scope = AgentRunScope.open(new AgentRunContext("other", "chat", root, "project"))) {
      var result =
          new PagedFileSearch(new Tools(), store)
              .read(new PagedFileSearch.Request(null, null, 512, 2048, 1, 1, cursor), root);
      assertEquals("INVALID_CURSOR", result.error().code());
    }
    try (var scope = AgentRunScope.open(owner)) {
      var expiredStore =
          ReadingTestStores.store(
              new StorageDatabase(data),
              Clock.fixed(now.plus(Duration.ofHours(25)), ZoneOffset.UTC));
      var result =
          new PagedFileSearch(new Tools(), expiredStore)
              .read(new PagedFileSearch.Request(null, null, 512, 2048, 1, 1, cursor), root);
      assertEquals("CURSOR_EXPIRED", result.error().code());
    }
  }
}
