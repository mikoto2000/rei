package dev.mikoto2000.rei.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Direct file tools must enforce the same boundary as snapshot reads. */
class FileOperationBoundaryTest {
  @TempDir Path temporary;

  @Test
  void outsideAndSensitiveFilesCannotBeReadChangedOrProbed() throws Exception {
    Path root = Files.createDirectory(temporary.resolve("project"));
    Path outside = Files.writeString(temporary.resolve("outside.txt"), "original\n");
    Path secret = Files.writeString(root.resolve(".env"), "original\n");
    var tools = new Tools();
    try (var scope = AgentRunScope.open(new AgentRunContext("run", "session", root))) {
      for (String path : List.of(outside.toString(), "../outside.txt", secret.toString(), ".env")) {
        assertThrows(IOException.class, () -> tools.readBinaryFile(path));
        assertThrows(IOException.class, () -> tools.writeBinaryFile(path, new byte[] {1}, false));
        assertThrows(IOException.class, () -> tools.deleteFile(path));
        assertThrows(IOException.class, () -> tools.copyFile(path, "copy.txt", false));
        assertThrows(IOException.class, () -> tools.moveFile(path, "moved.txt", false));
        var counts = tools.countFileLines(List.of(path));
        assertNull(counts.files().getFirst().lineCount());
        assertNotNull(counts.files().getFirst().error());
        assertThrows(
            IOException.class,
            () ->
                tools.writeMultiFile(
                    List.of(new Tools.WriteFileRequest(path, "changed", false, "UTF-8"))));
      }
      Files.writeString(root.resolve("source.txt"), "source");
      assertThrows(
          IOException.class,
          () ->
              tools.writeMultiFile(
                  List.of(
                      new Tools.WriteFileRequest("source.txt", "changed", false, "UTF-8"),
                      new Tools.WriteFileRequest("../outside.txt", "changed", false, "UTF-8"))));
      assertEquals("source", Files.readString(root.resolve("source.txt")));
      assertThrows(IOException.class, () -> tools.copyFile("source.txt", outside.toString(), true));
      assertThrows(IOException.class, () -> tools.moveFile("source.txt", "../outside.txt", true));
      assertThrows(IOException.class, () -> tools.createDirectories("../new-parent/child"));
      assertThrows(
          IOException.class,
          () -> tools.writeBinaryFile("../new-parent/file", new byte[] {1}, false));
    }
    assertEquals("original\n", Files.readString(outside));
    assertEquals("original\n", Files.readString(secret));
    assertFalse(Files.exists(temporary.resolve("new-parent")));
    assertFalse(Files.exists(root.resolve("copy.txt")));
    assertFalse(Files.exists(root.resolve("moved.txt")));
  }

  @Test
  void projectRelativeAndAbsolutePathsStillWorkWithMissingParents() throws Exception {
    Path root = Files.createDirectory(temporary.resolve("project"));
    var tools = new Tools();
    try (var scope = AgentRunScope.open(new AgentRunContext("run", "session", root))) {
      tools.createDirectories("nested/child");
      tools.writeBinaryFile("nested/input.bin", new byte[] {1, 2}, false);
      assertArrayEquals(
          new byte[] {1, 2}, tools.readBinaryFile(root.resolve("nested/input.bin").toString()));
      tools.copyFile("nested/input.bin", "copy.bin", false);
      tools.moveFile("copy.bin", "moved.bin", false);
      tools.deleteFile("moved.bin");
      assertFalse(Files.exists(root.resolve("moved.bin")));
      assertEquals(
          true,
          tools
              .writeMultiFile(
                  List.of(
                      new Tools.WriteFileRequest("new/parent/file.txt", "hello\n", false, "UTF-8")))
              .getFirst()
              .success());
      assertEquals(1L, tools.countFileLines(List.of("new/parent/file.txt")).totalLines());
    }
  }

  @Test
  @org.junit.jupiter.api.Tag("integration")
  void linkedParentCannotExposeOrOverwriteOutsideFiles() throws Exception {
    Path root = Files.createDirectory(temporary.resolve("project"));
    Path outside = Files.createDirectory(temporary.resolve("outside"));
    Files.writeString(outside.resolve("file.txt"), "original");
    Path link = root.resolve("link");
    if (System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("windows")) {
      var process =
          new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString(), outside.toString())
              .redirectErrorStream(true)
              .start();
      org.junit.jupiter.api.Assertions.assertTrue(
          process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS));
      assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes()));
    } else Files.createSymbolicLink(link, outside);
    var tools = new Tools();
    try (var scope = AgentRunScope.open(new AgentRunContext("run", "session", root))) {
      assertThrows(IOException.class, () -> tools.readBinaryFile("link/file.txt"));
      assertThrows(
          IOException.class, () -> tools.writeBinaryFile("link/new.bin", new byte[] {1}, false));
      assertThrows(IOException.class, () -> tools.createDirectories("link/new/child"));
      assertThrows(IOException.class, () -> tools.deleteFile("link/file.txt"));
    } finally {
      Files.delete(link);
    }
    assertEquals("original", Files.readString(outside.resolve("file.txt")));
    assertFalse(Files.exists(outside.resolve("new.bin")));
    assertFalse(Files.exists(outside.resolve("new")));
  }
}
