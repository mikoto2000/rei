package dev.mikoto2000.rei.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import dev.mikoto2000.rei.storage.StorageDatabase;
import dev.mikoto2000.rei.storage.StorageMigrationCoordinator;
import dev.mikoto2000.rei.tooldelivery.ReadingMetrics;
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
    try (var gate = new StorageMigrationCoordinator(data)) {
      gate.prepare();
    }
    var owner = new AgentRunContext("run", "chat", root, "project");
    var query =
        new Tools.GrepQuery(
            "hit", "a.txt", false, true, false, false, 0, 0, null, true, null, null);
    var reader = new Tools();
    try (var scope = AgentRunScope.open(owner)) {
      var first =
          new PagedFileSearch(reader, ReadingTestStores.store(new StorageDatabase(data)))
              .read(new PagedFileSearch.Request(List.of(query), 0, 512, 2048, 1, 1, null), root);
      assertNotNull(first.resultCursor());
      assertEquals(0, first.files().getFirst().matches().getFirst().queryIndex());
      var next =
          new PagedFileSearch(reader, ReadingTestStores.store(new StorageDatabase(data)))
              .read(
                  new PagedFileSearch.Request(null, null, 512, 2048, 1, 1, first.resultCursor()),
                  root);
      assertEquals(1000, next.files().getFirst().sections().getFirst().startLine());
      assertEquals(List.of("hit"), next.files().getFirst().sections().getFirst().content());
      assertTrue(!next.files().getFirst().truncated());
      Files.writeString(root.resolve("a.txt"), "changed");
      var stale =
          new PagedFileSearch(reader, ReadingTestStores.store(new StorageDatabase(data)))
              .read(
                  new PagedFileSearch.Request(null, null, 512, 2048, 1, 1, first.resultCursor()),
                  root);
      assertEquals("FILE_CHANGED", stale.error().code());
    }
    try (var scope = AgentRunScope.open(new AgentRunContext("other", "chat", root, "project"))) {
      var invalid =
          new PagedFileSearch(reader, ReadingTestStores.store(new StorageDatabase(data)))
              .read(new PagedFileSearch.Request(null, null, 512, 2048, 1, 1, "not-a-cursor"), root);
      assertTrue(invalid.files().isEmpty());
      assertEquals("INVALID_CURSOR", invalid.error().code());
    }
  }

  @Test
  void moreThan1024FilesResumeWithNoMissingTarget() throws Exception {
    Files.createDirectories(root.resolve("sources"));
    for (int i = 0; i < 1030; i++)
      Files.writeString(
          root.resolve(String.format("sources/%04d.txt", i)), i == 1029 ? "hit" : "miss");
    Path data = root.resolve("data");
    try (var gate = new StorageMigrationCoordinator(data)) {
      gate.prepare();
    }
    var query =
        new Tools.GrepQuery(
            "hit", "sources", false, true, false, false, 0, 0, null, true, null, null);
    try (var scope = AgentRunScope.open(new AgentRunContext("large", "chat", root, "project"))) {
      var search =
          new PagedFileSearch(new Tools(), ReadingTestStores.store(new StorageDatabase(data)));
      var page =
          search.read(
              new PagedFileSearch.Request(List.of(query), 0, 1024, 4096, 128, 100, null), root);
      int requests = 1;
      String scanId = page.progress().scanId();
      assertNotNull(scanId);
      var evaluator = new dev.mikoto2000.rei.core.stagnation.ProgressEvaluator(root);
      while (page.scanCursor() != null && requests++ < 12) {
        assertEquals(scanId, page.progress().scanId());
        assertTrue(
            evaluator
                .afterTool(
                    "searchFilePage",
                    "{}",
                    StorageDatabase.JSON.writeValueAsString(page),
                    evaluator.beforeTool("searchFilePage", "{}"))
                .stream()
                .anyMatch(e -> e.acquisitionProgress()));
        page =
            search.read(
                new PagedFileSearch.Request(null, null, 1024, 4096, 128, 100, page.scanCursor()),
                root);
      }
      assertEquals(null, page.error());
      assertEquals("sources/1029.txt", page.files().getFirst().path());
      assertEquals(9, requests);
      long largestCursor =
          new StorageDatabase(data)
              .read(
                  db -> {
                    try (var querySize = db.createStatement();
                        var rows =
                            querySize.executeQuery(
                                "SELECT MAX(bytes) FROM reading_state WHERE kind='search'")) {
                      return rows.getLong(1);
                    }
                  });
      assertTrue(largestCursor < 8192, "The shared inventory must not be copied into every cursor");
      assertEquals(0L, ReadingMetrics.snapshot(AgentRunScope.current()).get("unscannedFiles"));
    }
  }

  @Test
  @org.junit.jupiter.api.Tag("integration")
  void cursorResumesInAFreshJvm() throws Exception {
    Files.writeString(root.resolve("a.txt"), "hit\n" + "gap\n".repeat(998) + "hit\n");
    try (var gate = new StorageMigrationCoordinator(root.resolve("data"))) {
      gate.prepare();
    }
    String cursor;
    try (var scope = AgentRunScope.open(new AgentRunContext("restart", "chat", root, "project"))) {
      var query =
          new Tools.GrepQuery(
              "hit", "a.txt", false, true, false, false, 0, 0, null, true, null, null);
      cursor =
          new PagedFileSearch(
                  new Tools(), ReadingTestStores.store(new StorageDatabase(root.resolve("data"))))
              .read(new PagedFileSearch.Request(List.of(query), 0, 512, 2048, 1, 1, null), root)
              .resultCursor();
    }
    assertNotNull(cursor);
    String classpath =
        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    Path output = root.resolve("resumed.json");
    Path arguments = root.resolve("restart.args");
    Files.writeString(
        arguments,
        "-classpath\n\""
            + classpath.replace('\\', '/')
            + "\"\n"
            + ReadingCursorRestartProbe.class.getName()
            + "\n\""
            + root.toString().replace('\\', '/')
            + "\"\n"
            + cursor
            + "\n\""
            + output.toString().replace('\\', '/')
            + "\"\n");
    String binary = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
    var child =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", binary).toString(), "@" + arguments)
            .redirectOutput(root.resolve("restart.stdout").toFile())
            .redirectError(root.resolve("restart.stderr").toFile())
            .start();
    try {
      assertTrue(child.waitFor(60, java.util.concurrent.TimeUnit.SECONDS));
      assertEquals(0, child.exitValue(), Files.readString(root.resolve("restart.stderr")));
      assertEquals(
          1000,
          StorageDatabase.JSON
              .readTree(Files.readString(output))
              .path("files")
              .get(0)
              .path("sections")
              .get(0)
              .path("startLine")
              .asInt());
    } finally {
      if (child.isAlive()) {
        child.destroyForcibly();
        child.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
      }
    }
  }
}
