package dev.mikoto2000.rei.cli;

import static org.junit.jupiter.api.Assertions.*;

import dev.mikoto2000.rei.core.completion.ApprovalActionMenu;
import dev.mikoto2000.rei.core.completion.ApprovalMenuLineReader;
import java.io.*;
import java.util.concurrent.atomic.AtomicReference;
import org.jline.reader.*;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.Test;

class ApprovalActionMenuTest {
  private static final String ID = "ca880320-1482-4d84-8fb9-53c0b3234997";

  @Test
  void arrowsAndEnterSelectExactActionsWithoutTypingAnId() throws Exception {
    assertEquals("/approval show " + ID, read("\033[B\r", true));
    assertEquals("/approval approve " + ID + " --continue", read("\033[B\033[B\r", true));
    assertEquals("/approval deny " + ID, read("\033[B\033[B\033[B\r", true));
  }

  @Test
  void unrelatedSessionDoesNotOfferAnApprovalAction() throws Exception {
    assertEquals("", read("\033[B\r", false));
  }

  @Test
  void typedTextIsNotReplacedByApprovalActions() throws Exception {
    assertEquals("hello", read("hello\033[B\r", true));
  }

  @Test
  void emptyEnterDoesNotAutomaticallyApprove() throws Exception {
    assertEquals("", read("\r", true));
  }

  @Test
  void movingUpdatesOneMenuWithoutAppendingOutputAndEnterRemovesIt() throws Exception {
    var output = new ByteArrayOutputStream();
    try (var terminal =
        TerminalBuilder.builder()
            .system(false)
            .type("xterm")
            .streams(new ByteArrayInputStream(new byte[0]), output)
            .build()) {
      var reader = new ApprovalMenuLineReader(terminal);
      var menu = new ApprovalActionMenu(reader, () -> new ApprovalActionMenu.Owner("p", "s"));
      menu.enabled(true);
      menu.offer("p", "s", ID);
      int notificationBytes = output.size();
      reader.getWidgets().get("rei-approval-down").apply();
      reader.getWidgets().get("rei-approval-down").apply();
      reader.getWidgets().get("rei-approval-up").apply();
      assertEquals(notificationBytes, output.size(), "Navigation must not printAbove a new menu");
      String display = reader.getDisplayedBufferWithPrompts(new java.util.ArrayList<>()).toString();
      assertEquals(display.indexOf("承認 ["), display.lastIndexOf("承認 ["));
      assertTrue(display.contains("▶ 詳細確認"));
      assertFalse(display.contains("▶ 承認して続行"));
      assertThrows(
          IllegalStateException.class, () -> reader.getWidgets().get("rei-approval-enter").apply());
      assertFalse(
          reader
              .getDisplayedBufferWithPrompts(new java.util.ArrayList<>())
              .toString()
              .contains("▶"));
    }
  }

  private String read(String input, boolean sameOwner) throws Exception {
    try (var terminal =
        TerminalBuilder.builder()
            .system(false)
            .type("xterm")
            .streams(
                new ByteArrayInputStream(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                new ByteArrayOutputStream())
            .build()) {
      var reader = new ApprovalMenuLineReader(terminal);
      var menu = new ApprovalActionMenu(reader, () -> new ApprovalActionMenu.Owner("p", "s"));
      menu.enabled(true);
      menu.offer("p", sameOwner ? "s" : "other", ID);
      return reader.readLine("> ");
    }
  }

  @Test
  void aProjectChangeCancelsTheSelectedActionBeforeEnter() throws Exception {
    try (var terminal =
        TerminalBuilder.builder()
            .system(false)
            .dumb(true)
            .streams(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream())
            .build()) {
      var reader = new ApprovalMenuLineReader(terminal);
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

  @Test
  void aNewNotificationDoesNotChangeTheSelectedRequest() throws Exception {
    try (var terminal =
        TerminalBuilder.builder()
            .system(false)
            .dumb(true)
            .streams(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream())
            .build()) {
      var reader = new ApprovalMenuLineReader(terminal);
      var menu = new ApprovalActionMenu(reader, () -> new ApprovalActionMenu.Owner("p", "s"));
      menu.enabled(true);
      menu.offer("p", "s", ID);
      reader.getWidgets().get("rei-approval-down").apply();
      menu.offer("p", "s", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
      assertEquals("/approval show " + ID, reader.getBuffer().toString());
    }
  }
}
