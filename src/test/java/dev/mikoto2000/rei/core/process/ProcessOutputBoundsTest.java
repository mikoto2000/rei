package dev.mikoto2000.rei.core.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** プロセス出力の長い行と保持総量を、実プロセスなしで検証する。 */
class ProcessOutputBoundsTest {
  @Test
  void drainsOversizedLinesAndContinuesReadingBothDelimitedAndFinalOutput() {
    var buffer = new BoundedLineBuffer(200);
    String output = "x".repeat(4 * 1024 * 1024) + "\r\nready\nfinal";
    BackgroundProcessManager.readLines(
        new ByteArrayInputStream(output.getBytes(StandardCharsets.UTF_8)), buffer);

    var lines = buffer.tail(200);
    assertEquals(3, lines.size());
    assertEquals(BoundedLineBuffer.MAX_LINE_CHARACTERS, lines.getFirst().length());
    assertTrue(lines.getFirst().endsWith("... [truncated]"));
    assertEquals(List.of("ready", "final"), lines.subList(1, 3));
  }

  @Test
  void preservesEmptyLinesAndCrLfAcrossReadChunks() {
    var buffer = new BoundedLineBuffer(200);
    String first = "日".repeat(4095);
    String output = first + "\r\n\n\rnext\rfinal\n";
    BackgroundProcessManager.readLines(
        new ByteArrayInputStream(output.getBytes(StandardCharsets.UTF_8)), buffer);
    assertEquals(List.of(first, "", "", "next", "final"), buffer.tail(200));
  }

  @Test
  void boundsRetainedCharactersWhilePreservingTheNewestLines() {
    var buffer = new BoundedLineBuffer(200);
    for (int i = 0; i < 200; i++) {
      buffer.add(i + "x".repeat(BoundedLineBuffer.MAX_LINE_CHARACTERS));
    }
    var lines = buffer.tail(200);
    assertTrue(lines.size() < 200);
    assertTrue(lines.stream().mapToInt(String::length).sum() <= BoundedLineBuffer.MAX_CHARACTERS);
    assertTrue(lines.getLast().startsWith("199"));
    buffer.add("latest");
    assertEquals("latest", buffer.tail(1).getFirst());
  }

  @Test
  void stillEnforcesTheLineCountForShortLines() {
    var buffer = new BoundedLineBuffer(2);
    buffer.add("first");
    buffer.add("second");
    buffer.add("third");
    assertEquals(List.of("second", "third"), buffer.tail(2));
  }
}
