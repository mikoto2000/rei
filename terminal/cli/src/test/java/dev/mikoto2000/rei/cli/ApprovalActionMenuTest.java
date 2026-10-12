package dev.mikoto2000.rei.cli;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.util.concurrent.atomic.AtomicReference;
import org.jline.reader.*;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.Test;
import dev.mikoto2000.rei.core.completion.ApprovalActionMenu;

class ApprovalActionMenuTest {
  private static final String ID = "ca880320-1482-4d84-8fb9-53c0b3234997";
  @Test void arrowsAndEnterSelectExactActionsWithoutTypingAnId() throws Exception {
    assertEquals("/approval show " + ID, read("\033[B\r", true));
    assertEquals("/approval approve " + ID + " --continue", read("\033[B\033[B\r", true));
    assertEquals("/approval deny " + ID, read("\033[B\033[B\033[B\r", true));
  }
  @Test void unrelatedSessionDoesNotOfferAnApprovalAction() throws Exception {
    assertEquals("", read("\033[B\r", false));
  }
  @Test void typedTextIsNotReplacedByApprovalActions() throws Exception {
    assertEquals("hello", read("hello\033[B\r", true));
  }
  @Test void emptyEnterDoesNotAutomaticallyApprove() throws Exception {
    assertEquals("", read("\r", true));
  }
  private String read(String input, boolean sameOwner) throws Exception {
    try (var terminal = TerminalBuilder.builder().system(false).type("xterm")
        .streams(new ByteArrayInputStream(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)), new ByteArrayOutputStream()).build()) {
      var reader = LineReaderBuilder.builder().terminal(terminal).build();
      var menu = new ApprovalActionMenu(reader, () -> new ApprovalActionMenu.Owner("p", "s"));
      menu.enabled(true);
      menu.offer("p", sameOwner ? "s" : "other", ID);
      return reader.readLine("> ");
    }
  }
  @Test void aProjectChangeCancelsTheSelectedActionBeforeEnter() throws Exception {
    try (var terminal = TerminalBuilder.builder().system(false).dumb(true)
        .streams(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream()).build()) {
      var reader = LineReaderBuilder.builder().terminal(terminal).build();
      var owner = new AtomicReference<>(new ApprovalActionMenu.Owner("p", "s"));
      var menu = new ApprovalActionMenu(reader, owner::get);
      menu.enabled(true);
      menu.offer("p", "s", ID);
      reader.getWidgets().get("rei-approval-down").apply();
      reader.getWidgets().get("rei-approval-down").apply();
      owner.set(new ApprovalActionMenu.Owner("other", "s"));
      reader.getWidgets().get("rei-approval-enter").apply();
      assertEquals("", reader.getBuffer().toString());
    }
  }
  @Test void aNewNotificationDoesNotChangeTheSelectedRequest() throws Exception {
    try (var terminal = TerminalBuilder.builder().system(false).dumb(true)
        .streams(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream()).build()) {
      var reader = LineReaderBuilder.builder().terminal(terminal).build();
      var menu = new ApprovalActionMenu(reader, () -> new ApprovalActionMenu.Owner("p", "s"));
      menu.enabled(true);
      menu.offer("p", "s", ID);
      reader.getWidgets().get("rei-approval-down").apply();
      menu.offer("p", "s", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
      assertEquals("/approval show " + ID, reader.getBuffer().toString());
    }
  }
}
