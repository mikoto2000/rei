package dev.mikoto2000.rei.core.configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class VectorStorePaths {

  private VectorStorePaths() {
  }

  public static Path storeFile() {
    return Path.of(
        System.getProperty("user.home"),
        ".cache",
        "rei",
        "vector-store.json");
  }

  public static void createParentDirectories() throws IOException {
    Files.createDirectories(storeFile().getParent());
  }
}
