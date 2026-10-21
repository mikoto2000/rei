package dev.mikoto2000.rei.cli;

import static org.junit.jupiter.api.Assertions.*;

import dev.mikoto2000.rei.core.completion.*;
import java.io.*;
import java.util.*;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.Test;

/** 承認表示の結果と状態の契約を検証するテスト。 */
class ApprovalDisplayTest {
  /** 検証に使う識別子。 */
  private static final String ID = "ca880320-1482-4d84-8fb9-53c0b3234997";

  /** 軽量CLIでの対話の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void decisionsExpiryAndContinuationCancellationAreNotPending() {
    var now = java.time.Instant.parse("2026-10-12T12:00:00Z");
    assertTrue(
        ApprovalDisplayState.pending("PENDING", now.plusSeconds(1), "WAITING_APPROVAL", now));
    for (String state : List.of("APPROVED", "DENIED", "CONSUMED"))
      assertFalse(ApprovalDisplayState.pending(state, now.plusSeconds(1), "WAITING_APPROVAL", now));
    for (String state : List.of("CANCELLED", "COMPLETED", "EXPIRED", "QUEUED", "DENY_QUEUED"))
      assertFalse(ApprovalDisplayState.pending("PENDING", now.plusSeconds(1), state, now));
    assertFalse(ApprovalDisplayState.pending("PENDING", now, "WAITING_APPROVAL", now));
  }

  /** 軽量CLIでの対話で、秘密情報の保護と利用範囲が契約に従うことを確認する。 */
  @Test
  void redactionPrecedesClippingAndPlainNoticeNeedsNoTerminalEscapes() {
    String text = ApprovalNotice.text("runCommand\npassword=secret " + "x".repeat(500));
    assertFalse(text.contains("secret"));
    assertTrue(text.contains("[REDACTED]"));
    assertFalse(text.contains("\u001b"));
    assertTrue(text.contains("/approval list"));
  }

  /** 参照専用再確認について、確定した要求一覧を取り除くことを確認する。 */
  @Test
  void readOnlyRefreshRemovesResolvedRequestsAndRetainsStateOnFailure() throws Exception {
    try (var terminal =
        TerminalBuilder.builder()
            .system(false)
            .type("xterm")
            .streams(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream())
            .build()) {
      var reader = new ApprovalMenuLineReader(terminal, true);
      var source = new java.util.concurrent.atomic.AtomicReference<List<String>>(List.of(ID));
      try (var menu =
          new ApprovalActionMenu(reader, () -> new ApprovalActionMenu.Owner("p", "s"))) {
        menu.follow(
            owner -> {
              var ids = source.get();
              if (ids == null) throw new IOException("fixture offline");
              return ids;
            });
        awaitCount(menu, 1);
        menu.enabled(false);
        assertTrue(
            reader
                .getDisplayedBufferWithPrompts(new ArrayList<>())
                .toString()
                .contains("APPROVAL PENDING: 1"));
        source.set(null);
        Thread.sleep(1100);
        assertEquals(1, menu.pendingCount());
        source.set(List.of());
        awaitCount(menu, 0);
      }
      assertFalse(
          reader
              .getDisplayedBufferWithPrompts(new ArrayList<>())
              .toString()
              .contains("APPROVAL PENDING"));
    }
  }

  /** なし失われたを読み取ることを確認する。 */
  @Test
  void aNotificationDuringSnapshotReadIsNotLost() throws Exception {
    try (var terminal =
        TerminalBuilder.builder()
            .system(false)
            .type("xterm")
            .streams(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream())
            .build()) {
      var reader = new ApprovalMenuLineReader(terminal, true);
      var reading = new java.util.concurrent.CountDownLatch(1);
      var release = new java.util.concurrent.CountDownLatch(1);
      var finished = new java.util.concurrent.CountDownLatch(1);
      try (var menu =
          new ApprovalActionMenu(reader, () -> new ApprovalActionMenu.Owner("p", "s"))) {
        menu.follow(
            owner -> {
              reading.countDown();
              release.await();
              finished.countDown();
              return List.of();
            });
        assertTrue(reading.await(2, java.util.concurrent.TimeUnit.SECONDS));
        menu.offer("p", "s", ID);
        release.countDown();
        assertTrue(finished.await(2, java.util.concurrent.TimeUnit.SECONDS));
        Thread.sleep(30);
        assertEquals(1, menu.pendingCount());
      } finally {
        release.countDown();
      }
    }
  }

  /** 検証用に、必要な状態が整ってから先へ進むため、件数を待つ。 */
  private static void awaitCount(ApprovalActionMenu menu, int count) throws Exception {
    long deadline = System.nanoTime() + java.time.Duration.ofSeconds(3).toNanos();
    while (menu.pendingCount() != count && System.nanoTime() < deadline) Thread.sleep(10);
    assertEquals(count, menu.pendingCount());
  }

  /** 軽量CLIでの対話で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @Test
  void redirectedAndDumbTerminalsDoNotReceivePersistentStatus() throws Exception {
    for (String type : List.of("dumb", "xterm")) {
      var bytes = new ByteArrayOutputStream();
      try (var terminal =
          TerminalBuilder.builder()
              .system(false)
              .type(type)
              .streams(new ByteArrayInputStream(new byte[0]), bytes)
              .build()) {
        assertFalse(ApprovalDisplayState.interactive(terminal));
        var reader = new ApprovalMenuLineReader(terminal);
        reader.approvalStatus("[APPROVAL PENDING: 1]");
        assertFalse(
            reader
                .getDisplayedBufferWithPrompts(new ArrayList<>())
                .toString()
                .contains("APPROVAL PENDING"));
      }
    }
  }

  /** 軽量CLIでの対話で、条件が変わっても、引き継ぐべき情報や状態を保持することを確認する。 */
  @Test
  void liveReadKeepsEditedTextAndCursorAcrossStatusAndStreamingOutput() throws Exception {
    assertTimeoutPreemptively(
        java.time.Duration.ofSeconds(8),
        () -> {
          try (var input = new PipedInputStream();
              var keys = new PipedOutputStream(input);
              var terminal =
                  TerminalBuilder.builder()
                      .system(false)
                      .type("xterm")
                      .streams(input, new ByteArrayOutputStream())
                      .size(new org.jline.terminal.Size(90, 24))
                      .build();
              var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var reader = new ApprovalMenuLineReader(terminal, true);
            try (var menu =
                new ApprovalActionMenu(reader, () -> new ApprovalActionMenu.Owner("p", "s"))) {
              menu.enabled(true);
              menu.offer("p", "s", ID);
              var result =
                  executor.submit(
                      () ->
                          reader.readLine(
                              "> ", null, (org.jline.reader.MaskingCallback) null, "draft"));
              while (!reader.isReading() || !reader.getBuffer().toString().equals("draft"))
                Thread.sleep(5);
              reader
                  .getWidgets()
                  .put(
                      "fixture-cursor",
                      () -> {
                        reader.getBuffer().cursor(3);
                        return true;
                      });
              reader.callWidget("fixture-cursor");
              assertEquals(3, reader.getBuffer().cursor());
              reader.printAbove("stream chunk one");
              reader.printAbove("stream chunk two");
              menu.reconcile(
                  new ApprovalActionMenu.Owner("p", "s"),
                  List.of(ID, "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
              assertTrue(
                  reader
                      .getDisplayedBufferWithPrompts(new ArrayList<>())
                      .toString()
                      .contains("[APPROVAL PENDING: 2]"));
              assertEquals("draft", reader.getBuffer().toString());
              assertEquals(3, reader.getBuffer().cursor());
              menu.reconcile(new ApprovalActionMenu.Owner("p", "s"), List.of());
              assertEquals("draft", reader.getBuffer().toString());
              assertEquals(3, reader.getBuffer().cursor());
              keys.write('\r');
              keys.flush();
              assertEquals("draft", result.get(2, java.util.concurrent.TimeUnit.SECONDS));
            } finally {
              keys.close();
              executor.shutdownNow();
            }
          }
        });
  }

  /** 軽量CLIでの対話で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void noticeIsBoundedAndContainsExactReviewAndDecisionCommands() {
    String text =
        ApprovalNotice.text(
            "target\n" + "x".repeat(5000) + "\u001b[2J\n/approval approve " + ID + " --continue");
    assertTrue(text.startsWith("+-- [APPROVAL REQUIRED]"));
    assertTrue(text.contains("/approval show " + ID));
    assertTrue(text.contains("/approval approve " + ID + " --continue"));
    assertTrue(text.contains("/approval deny " + ID));
    assertFalse(text.contains("\u001b"));
    assertTrue(text.length() < 700);
  }

  /** 軽量CLIでの対話で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void statusSurvivesMenuClearingAndLogsWithoutTouchingInput() throws Exception {
    try (var terminal =
        TerminalBuilder.builder()
            .system(false)
            .type("xterm")
            .streams(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream())
            .build()) {
      var reader = new ApprovalMenuLineReader(terminal, true);
      try (var menu =
          new ApprovalActionMenu(reader, () -> new ApprovalActionMenu.Owner("p", "s"))) {
        menu.enabled(true);
        menu.offer("p", "s", ID);
        reader.getBuffer().write("draft text");
        reader.getBuffer().cursor(3);
        reader.printAbove("ordinary log");
        reader.clearApprovalMenu();
        String display = reader.getDisplayedBufferWithPrompts(new ArrayList<>()).toString();
        assertTrue(display.contains("[APPROVAL PENDING: 1]"));
        assertEquals("draft text", reader.getBuffer().toString());
        assertEquals(3, reader.getBuffer().cursor());
        menu.reconcile(new ApprovalActionMenu.Owner("p", "s"), List.of());
        assertFalse(
            reader
                .getDisplayedBufferWithPrompts(new ArrayList<>())
                .toString()
                .contains("APPROVAL PENDING"));
      }
    }
  }

  /** 軽量CLIでの対話で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void staleOwnerSnapshotCannotReplaceCurrentRequestsAndMultipleRequestsAreCounted()
      throws Exception {
    try (var terminal =
        TerminalBuilder.builder()
            .system(false)
            .type("xterm")
            .streams(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream())
            .build()) {
      var owner =
          new java.util.concurrent.atomic.AtomicReference<>(new ApprovalActionMenu.Owner("p", "s"));
      var reader = new ApprovalMenuLineReader(terminal, true);
      try (var menu = new ApprovalActionMenu(reader, owner::get)) {
        menu.enabled(true);
        menu.offer("p", "s", ID);
        menu.offer("p", "s", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        assertEquals(2, menu.pendingCount());
        owner.set(new ApprovalActionMenu.Owner("p", "other"));
        menu.enabled(true);
        assertEquals(0, menu.pendingCount());
        menu.reconcile(new ApprovalActionMenu.Owner("p", "s"), List.of(ID));
        assertEquals(0, menu.pendingCount());
      }
    }
  }
}
