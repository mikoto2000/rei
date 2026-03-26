package dev.mikoto2000.rei.core.datasource;

import java.nio.file.Files;
import java.nio.file.Path;

public final class ReiPaths {

  private ReiPaths() {
  }

  public static Path memoryDbPath() {
    String os = System.getProperty("os.name").toLowerCase();

    Path path;
    if (os.contains("win")) {
      path = Path.of(System.getenv("LOCALAPPDATA"), "rei", "memory.db");
    } else {
      path = Path.of(System.getProperty("user.home"), ".config", "rei", "memory.db");
    }

    return path;
  }

  public static void ensureParentDirectoryExists(Path filePath) throws Exception {
    Files.createDirectories(filePath.getParent());
  }
}
