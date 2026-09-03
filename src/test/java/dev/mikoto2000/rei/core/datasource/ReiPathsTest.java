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
  void vectorStoreDbPathUsesWorkingDirectory() {
    Path workDirectory = Path.of("/work/rei");
    Path expected = Path.of(
        "/work/rei",
        ".rei",
        "vectorstore.db");

    assertEquals(expected, ReiPaths.vectorStoreDbPath(workDirectory));
  }

  @Test
  void memoryConsolidationDbPathUsesWorkingDirectory() {
    Path workDirectory = Path.of("/work/rei");
    Path expected = Path.of(
        "/work/rei",
        ".rei",
        "memory-consolidation.db");

    assertEquals(expected, ReiPaths.memoryConsolidationDbPath(workDirectory));
  }

  @Test
  void curiosityDbPathUsesWorkingDirectory() {
    Path workDirectory = Path.of("/work/rei");
    Path expected = Path.of(
        "/work/rei",
        ".rei",
        "curiosity.db");

    assertEquals(expected, ReiPaths.curiosityDbPath(workDirectory));
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

  @Test
  void conversationLogsDirectoryUsesWorkingDirectory() {
    Path workDirectory = Path.of("/work/rei");

    assertEquals(Path.of("/work/rei", ".rei", "conversation-logs"),
        ReiPaths.conversationLogsDirectory(workDirectory));
  }

  @Test
  void profileLogPathUsesWorkingDirectory() {
    Path workDirectory = Path.of("/work/rei");

    assertEquals(Path.of("/work/rei", ".rei", "profile.log"),
        ReiPaths.profileLogPath(workDirectory));
  }

  @Test
  void configFilePathUsesWorkingDirectory() {
    Path workDirectory = Path.of("/work/rei");
    Path expected = Path.of(
        "/work/rei",
        ".rei",
        "application.yaml");

    assertEquals(expected, ReiPaths.configFilePath(workDirectory));
  }

  @Test
  void additionalSystemPromptFilePathUsesWorkingDirectory() {
    Path workDirectory = Path.of("/work/rei");
    Path expected = Path.of(
        "/work/rei",
        ".rei",
        "additional-system-prompt.md");

    assertEquals(expected, ReiPaths.additionalSystemPromptFilePath(workDirectory));
  }

  @Test
  void projectsFilePathUsesWorkingDirectory() {
    Path workDirectory = Path.of("/work/rei");
    Path expected = Path.of(
        "/work/rei",
        ".rei",
        "projects");

    assertEquals(expected, ReiPaths.projectsFilePath(workDirectory));
  }
}
