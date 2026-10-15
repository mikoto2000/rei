package dev.mikoto2000.rei.checkpoint;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import org.junit.jupiter.api.Test;

class ResumeOverviewPresentationTest {
  ResumeOverview overview(String project, long revision, long count) {
    return new ResumeOverview(
        project,
        project,
        count,
        new ResumeOverview.Candidate(
            "task",
            revision,
            revision,
            Instant.parse("2026-10-13T09:42:00Z"),
            "INTERRUPTED",
            "依頼",
            "未記録",
            "検証",
            "再開前の照合が必要",
            false),
        project + revision + count);
  }

  @Test
  void startupRestoreSameSelectionAndSessionDoNotDuplicateDetails() {
    var history = new ResumeOverview.DisplayHistory();
    var a = overview("A", 1, 3);
    assertTrue(history.present(a, ZoneOffset.UTC, false).contains("最新の再開候補"));
    assertEquals("", history.present(a, ZoneOffset.UTC, false));
    history.present(overview("B", 1, 1), ZoneOffset.UTC, false);
    assertTrue(history.present(a, ZoneOffset.UTC, false).contains("変更なし"));
    assertTrue(history.present(overview("A", 2, 3), ZoneOffset.UTC, false).contains("最新の再開候補"));
    assertTrue(history.present(overview("A", 2, 4), ZoneOffset.UTC, false).contains("最新の再開候補"));
    assertTrue(history.present(overview("A", 2, 3), ZoneOffset.UTC, true).contains("最新の再開候補"));
  }

  @Test
  void connectionsAndReconnectsHaveIndependentHistory() {
    var a = overview("A", 1, 1);
    for (int i = 0; i < 3; i++)
      assertTrue(
          new ResumeOverview.DisplayHistory()
              .present(a, ZoneOffset.UTC, false)
              .contains("最新の再開候補"));
  }

  @Test
  void timezoneAndExactCommandsAndOtherCountAreIncluded() {
    String text = overview("A", 1, 3).detail(ZoneId.of("America/New_York"));
    assertTrue(text.contains("2026-10-13 05:42 EDT"), text);
    assertTrue(text.contains("/resume show task"));
    assertTrue(text.contains("/resume task"));
    assertTrue(text.contains("ほか2件"));
  }

  @Test
  void controlsAndSecretsAreRemovedBeforeUnicodeClipping() {
    String text =
        ResumeOverview.safe(
            "\u001b[31mhello\npassword=supersecret token=abc \u001b[0m" + "😀".repeat(200), 80);
    assertFalse(text.contains("supersecret"));
    assertFalse(text.contains("abc"));
    assertFalse(text.contains("\u001b"));
    assertFalse(text.contains("\n"));
    assertTrue(text.codePointCount(0, text.length()) <= 80);
    assertEquals("未記録", ResumeOverview.safe(null, 80));
    assertEquals("visible", ResumeOverview.safe("\u001b]0;untrusted title\u0007visible\u202e", 80));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ResumeOverview.Candidate(
                "task\n/resume evil",
                1,
                1,
                Instant.EPOCH,
                "FAILED",
                "r",
                "c",
                "n",
                "notice",
                false));
  }

  @Test
  void disappearingCandidatesResetTheHistory() {
    var history = new ResumeOverview.DisplayHistory();
    var a = overview("A", 1, 1);
    history.present(a, ZoneOffset.UTC, false);
    assertEquals(
        "", history.present(new ResumeOverview("A", "A", 0, null, "empty"), ZoneOffset.UTC, false));
    assertTrue(history.present(a, ZoneOffset.UTC, false).contains("最新の再開候補"));
  }

  @Test
  void extremelyLongQuotedJsonSecretsCannotOverflowTheDisplayStack() {
    String secret = "x".repeat(100000);
    assertEquals(
        "{\"password\":\"[REDACTED]\"}",
        ResumeOverview.safe("{\"password\":\"" + secret + "\"}", 80));
    assertEquals(
        "{\"token\":\"[REDACTED]\"}",
        ResumeOverview.safe("{\"token\":\"" + ("\\\\\\\"".repeat(20000)) + "\"}", 80));
  }
}
