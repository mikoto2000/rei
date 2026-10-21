package dev.mikoto2000.rei.cli;

import static org.junit.jupiter.api.Assertions.*;

import dev.mikoto2000.rei.core.completion.ApprovalActionMenu;
import dev.mikoto2000.rei.core.completion.ApprovalMenuLineReader;
import java.io.*;
import java.util.concurrent.atomic.AtomicReference;
import org.jline.reader.*;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.Test;

/** 承認操作操作の選択肢の結果と状態の契約を検証するテスト。 */
class ApprovalActionMenuTest {
  /** 検証に使う識別子。 */
  private static final String ID = "ca880320-1482-4d84-8fb9-53c0b3234997";

  /** 軽量CLIでの対話で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void arrowsAndEnterSelectExactActionsWithoutTypingAnId() throws Exception {
    assertEquals("/approval show " + ID, read("\033[B\r", true));
    assertEquals("/approval approve " + ID + " --continue", read("\033[B\033[B\r", true));
    assertEquals("/approval deny " + ID, read("\033[B\033[B\033[B\r", true));
  }

  /** 軽量CLIでの対話の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void unrelatedSessionDoesNotOfferAnApprovalAction() throws Exception {
    assertEquals("", read("\033[B\r", false));
  }

  /** 軽量CLIでの対話の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void typedTextIsNotReplacedByApprovalActions() throws Exception {
    assertEquals("hello", read("hello\033[B\r", true));
  }

  /** 軽量CLIでの対話で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void emptyEnterDoesNotAutomaticallyApprove() throws Exception {
    assertEquals("", read("\r", true));
  }

  /** 軽量CLIでの対話で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
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

  /** 検証用に、指定された対象の内容を読み取り、後続の処理で参照できるようにする。 */
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

  /** 軽量CLIでの対話の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
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

  /** 軽量CLIでの対話で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
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
