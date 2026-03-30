package dev.mikoto2000.rei.core.datasource;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class ReiPathsTest {

  @Test
  void memoryDbPathUsesWorkingDirectory() {
    Path workDirectory = Path.of("/work/rei");
    Path expected = Path.of(
        "/work/rei",
        ".rei",
        "memory.db");

    assertEquals(expected, ReiPaths.memoryDbPath(workDirectory));
  }

  @Test
  void historyFilePathUsesWorkingDirectory() {
    Path workDirectory = Path.of("/work/rei");
    Path expected = Path.of(
        "/work/rei",
        ".rei",
        "history");

    assertEquals(expected, ReiPaths.historyFilePath(workDirectory));
  }
}
