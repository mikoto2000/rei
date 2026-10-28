package dev.mikoto2000.rei.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import dev.mikoto2000.rei.storage.StorageDatabase;
import dev.mikoto2000.rei.storage.StorageMigrationCoordinator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PagedFileSearchTest {
  @TempDir Path root;

  @Test
  void distantHitsResumeWithoutInterveningSourceAndSurviveStoreRecreation() throws Exception {
    Files.writeString(root.resolve("a.txt"), "hit\n" + "irrelevant\n".repeat(998) + "hit\n");
    Path data = root.resolve("data");
    try (var gate = new StorageMigrationCoordinator(data)) { gate.prepare(); }
    var owner = new AgentRunContext("run", "chat", root, "project");
    var query = new Tools.GrepQuery("hit", "a.txt", false, true, false, false, 0, 0, null, true, null, null);
    var reader = new Tools();
    try (var scope = AgentRunScope.open(owner)) {
      var first = new PagedFileSearch(reader, new ReadingStateStore(new StorageDatabase(data)))
          .read(new PagedFileSearch.Request(List.of(query), 0, 512, 2048, 1, 1, null), root);
      assertNotNull(first.resultCursor());
      var next = new PagedFileSearch(reader, new ReadingStateStore(new StorageDatabase(data)))
          .read(new PagedFileSearch.Request(null, null, 512, 2048, 1, 1, first.resultCursor()), root);
      assertEquals(1000, next.files().getFirst().sections().getFirst().startLine());
      assertEquals(List.of("hit"), next.files().getFirst().sections().getFirst().content());
      Files.writeString(root.resolve("a.txt"), "changed");
      var stale = new PagedFileSearch(reader, new ReadingStateStore(new StorageDatabase(data)))
          .read(new PagedFileSearch.Request(null, null, 512, 2048, 1, 1, first.resultCursor()), root);
      assertEquals("FILE_CHANGED", stale.error().code());
    }
    try (var scope = AgentRunScope.open(new AgentRunContext("other", "chat", root, "project"))) {
      var invalid = new PagedFileSearch(reader, new ReadingStateStore(new StorageDatabase(data)))
          .read(new PagedFileSearch.Request(null, null, 512, 2048, 1, 1, "not-a-cursor"), root);
      assertTrue(invalid.files().isEmpty());
      assertEquals("INVALID_CURSOR", invalid.error().code());
    }
  }
}
