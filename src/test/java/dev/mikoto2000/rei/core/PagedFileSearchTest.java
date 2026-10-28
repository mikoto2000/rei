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
      assertEquals(0, first.files().getFirst().matches().getFirst().queryIndex());
      var next = new PagedFileSearch(reader, new ReadingStateStore(new StorageDatabase(data)))
          .read(new PagedFileSearch.Request(null, null, 512, 2048, 1, 1, first.resultCursor()), root);
      assertEquals(1000, next.files().getFirst().sections().getFirst().startLine());
      assertEquals(List.of("hit"), next.files().getFirst().sections().getFirst().content());
      assertTrue(!next.files().getFirst().truncated());
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

  @Test
  void moreThan1024FilesResumeWithNoMissingTarget() throws Exception {
    Files.createDirectories(root.resolve("sources"));
    for (int i = 0; i < 1030; i++) Files.writeString(root.resolve(String.format("sources/%04d.txt", i)), i == 1029 ? "hit" : "miss");
    Path data = root.resolve("data");
    try (var gate = new StorageMigrationCoordinator(data)) { gate.prepare(); }
    var query = new Tools.GrepQuery("hit", "sources", false, true, false, false, 0, 0, null, true, null, null);
    try (var scope = AgentRunScope.open(new AgentRunContext("large", "chat", root, "project"))) {
      var search = new PagedFileSearch(new Tools(), new ReadingStateStore(new StorageDatabase(data)));
      var page = search.read(new PagedFileSearch.Request(List.of(query), 0, 1024, 4096, 128, 100, null), root);
      int requests = 1;
      while (page.scanCursor() != null && requests++ < 12) {
        page = search.read(new PagedFileSearch.Request(null, null, 1024, 4096, 128, 100, page.scanCursor()), root);
      }
      assertEquals(null, page.error());
      assertEquals("sources/1029.txt", page.files().getFirst().path());
      assertEquals(9, requests);
    }
  }
}
