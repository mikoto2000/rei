package dev.mikoto2000.rei.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.core.completion.MeetingSelectionMenu;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Size;
import org.jline.terminal.impl.DumbTerminal;
import org.junit.jupiter.api.Test;

class ChatEndpointMenuTest {
  @Test
  void endpointChoiceShowsStateAndSelectsWithArrowsWithoutClaimingToResumeARun() throws Exception {
    var output = new ByteArrayOutputStream();
    try (var terminal = new DumbTerminal(new ByteArrayInputStream(new byte[0]), output)) {
      terminal.setSize(Size.of(120, 10));
      var reader = LineReaderBuilder.builder().terminal(terminal).build();
      reader.runMacro("\033[B\r");
      var selected = MeetingSelectionMenu.chooseOne(reader, candidates());
      assertEquals("subscription", selected.orElseThrow());
      var text = output.toString(StandardCharsets.UTF_8);
      assertTrue(text.contains("[active]"));
      assertTrue(text.contains("unavailable: authentication"));
      assertFalse(text.contains("再開"));
    }
  }

  @Test
  void cancellationNeverReturnsTheHighlightedEndpoint() throws Exception {
    try (var terminal =
        new DumbTerminal(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream())) {
      terminal.setSize(Size.of(120, 10));
      var reader = LineReaderBuilder.builder().terminal(terminal).build();
      for (var cancel : List.of("\033", "\003", "\004")) {
        reader.runMacro("\033[B" + cancel);
        assertTrue(MeetingSelectionMenu.chooseOne(reader, candidates()).isEmpty());
      }
    }
  }

  private static List<MeetingSelectionMenu.Candidate> candidates() {
    return List.of(
        new MeetingSelectionMenu.Candidate("local", "local [active]"),
        new MeetingSelectionMenu.Candidate(
            "subscription", "subscription unavailable: authentication"));
  }
}
