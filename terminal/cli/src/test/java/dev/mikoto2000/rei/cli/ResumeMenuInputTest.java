package dev.mikoto2000.rei.cli;

import static org.junit.jupiter.api.Assertions.*;

import dev.mikoto2000.rei.core.completion.MeetingSelectionMenu;
import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Size;
import org.jline.terminal.impl.DumbTerminal;
import org.junit.jupiter.api.Test;

class ResumeMenuInputTest {
  @Test
  void splitArrowSequencesAfterReadLineDoNotCancelTheMenu() throws Exception {
    for (String suffix : List.of("[B", "OB", "[A", "OA")) {
      var rendered = new CountDownLatch(1);
      var output = output(rendered);
      var input = new PipedInputStream();
      var executor = Executors.newSingleThreadExecutor();
      try (var writer = new PipedOutputStream(input);
          var terminal = new DumbTerminal(input, output)) {
        terminal.setSize(Size.of(80, 10));
        var reader = LineReaderBuilder.builder().terminal(terminal).build();
        var choice =
            executor.submit(() -> {
              assertEquals("resume", reader.readLine("> "));
              String attributes = terminal.getAttributes().toString();
              var keymap = reader.getKeyMaps().get("main");
              var selected =
                  MeetingSelectionMenu.chooseOne(
                      reader,
                      List.of(
                          new MeetingSelectionMenu.Candidate("a", "First"),
                          new MeetingSelectionMenu.Candidate("b", "Second"),
                          new MeetingSelectionMenu.Candidate("c", "Third")));
              assertEquals(attributes, terminal.getAttributes().toString());
              assertSame(keymap, reader.getKeyMaps().get("main"));
              return selected;
            });
        writer.write("resume\r".getBytes(StandardCharsets.UTF_8));
        writer.flush();
        assertTrue(rendered.await(5, TimeUnit.SECONDS));
        writer.write(27);
        writer.flush();
        Thread.sleep(250);
        assertFalse(choice.isDone(), "Arrow prefix was interpreted as Escape: " + suffix);
        writer.write((suffix + "\r").getBytes(StandardCharsets.UTF_8));
        writer.flush();
        assertEquals(
            suffix.endsWith("B") ? "b" : "c", choice.get(5, TimeUnit.SECONDS).orElseThrow());
        var nextLine = executor.submit(() -> reader.readLine("> "));
        writer.write("follow-up\r".getBytes(StandardCharsets.UTF_8));
        writer.flush();
        assertEquals("follow-up", nextLine.get(5, TimeUnit.SECONDS));
      } finally {
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
      }
    }
  }

  @Test
  void standaloneEscapeCancelsWithoutWaitingForAnotherKeyOrEof() throws Exception {
    var rendered = new CountDownLatch(1);
    var input = new PipedInputStream();
    var executor = Executors.newSingleThreadExecutor();
    try (var writer = new PipedOutputStream(input);
        var terminal = new DumbTerminal(input, output(rendered))) {
      terminal.setSize(Size.of(80, 10));
      var reader = LineReaderBuilder.builder().terminal(terminal).build();
      var choice =
          executor.submit(() ->
              MeetingSelectionMenu.chooseOne(
                  reader, List.of(new MeetingSelectionMenu.Candidate("a", "First"))));
      assertTrue(rendered.await(5, TimeUnit.SECONDS));
      writer.write(27);
      writer.flush();
      assertTrue(choice.get(5, TimeUnit.SECONDS).isEmpty());
    } finally {
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  private static ByteArrayOutputStream output(CountDownLatch rendered) {
    return new ByteArrayOutputStream() {
      @Override
      public synchronized void write(byte[] bytes, int offset, int length) {
        super.write(bytes, offset, length);
        if (toString(StandardCharsets.UTF_8).contains("Enter")) rendered.countDown();
      }
    };
  }
}
