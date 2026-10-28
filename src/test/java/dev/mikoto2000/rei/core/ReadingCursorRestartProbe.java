package dev.mikoto2000.rei.core;

import dev.mikoto2000.rei.tooldelivery.ReadingStateStore;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import dev.mikoto2000.rei.storage.StorageDatabase;
import java.nio.file.Files;
import java.nio.file.Path;

/** Fresh-JVM reader used by the persisted search restart integration test. */
public final class ReadingCursorRestartProbe {
  private ReadingCursorRestartProbe() {}

  public static void main(String[] args) throws Exception {
    Path root = Path.of(args[0]);
    try (var scope = AgentRunScope.open(new AgentRunContext("restart", "chat", root, "project"))) {
      var search =
          new PagedFileSearch(
              new Tools(), ReadingTestStores.store(new StorageDatabase(root.resolve("data"))));
      var page =
          search.read(new PagedFileSearch.Request(null, null, 512, 2048, 1, 1, args[1]), root);
      Files.writeString(Path.of(args[2]), StorageDatabase.JSON.writeValueAsString(page));
      if (page.error() != null) throw new IllegalStateException(page.error().code());
    }
  }
}
