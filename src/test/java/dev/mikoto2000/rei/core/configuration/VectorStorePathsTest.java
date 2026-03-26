package dev.mikoto2000.rei.core.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class VectorStorePathsTest {

  @Test
  void storeFileUsesUserHome() {
    Path expected = Path.of(
        System.getProperty("user.home"),
        ".cache",
        "rei",
        "vector-store.json");

    assertEquals(expected, VectorStorePaths.storeFile());
  }
}
