package dev.mikoto2000.rei.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import dev.mikoto2000.rei.storage.StorageDatabase;
import dev.mikoto2000.rei.storage.StorageMigrationCoordinator;
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
  void nativeReadErrorsHaveStructuredCategories() throws Exception {
    var result =
        new Tools()
            .readMultiFile(List.of(new Tools.ReadFileRequest("missing.txt", null, null)), root)
            .getFirst();
    assertEquals("FILE_NOT_FOUND", result.problem().code());
  }

  @Test
  void aValidCursorCannotCrossRunsAndExpirationIsDistinct() throws Exception {
    Files.writeString(root.resolve("a.txt"), "hit\nno\nhit\n");
    Path data = root.resolve("data");
    try (var gate = new StorageMigrationCoordinator(data)) {
      gate.prepare();
    }
    var now = Instant.parse("2026-10-28T00:00:00Z");
    var store = new ReadingStateStore(new StorageDatabase(data), Clock.fixed(now, ZoneOffset.UTC));
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
          new ReadingStateStore(
              new StorageDatabase(data),
              Clock.fixed(now.plus(Duration.ofHours(25)), ZoneOffset.UTC));
      var result =
          new PagedFileSearch(new Tools(), expiredStore)
              .read(new PagedFileSearch.Request(null, null, 512, 2048, 1, 1, cursor), root);
      assertEquals("CURSOR_EXPIRED", result.error().code());
    }
  }
}
