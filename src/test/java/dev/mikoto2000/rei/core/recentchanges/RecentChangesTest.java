package dev.mikoto2000.rei.core.recentchanges;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/** 直近変更一覧の結果と状態の契約を検証するテスト。 */
class RecentChangesTest {

  /** 検証に使うタイムゾーン。 */
  private static final ZoneId ZONE = ZoneId.of("Asia/Tokyo");

  /** {@code recentChanges} の条件を再現するための補助情報を返す。 */
  private RecentChanges recentChanges(int maxEntries, Instant start) {
    return new RecentChanges(maxEntries, Clock.fixed(start, ZONE));
  }

  /** 直近の変更の把握で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void newRecentChangesIsEmpty() {
    RecentChanges changes = recentChanges(20, Instant.parse("2026-08-17T00:00:00Z"));
    assertTrue(changes.isEmpty());
    assertEquals(0, changes.entries().size());
  }

  /** 直近の変更の把握で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void createCanBeRecorded() {
    RecentChanges changes = recentChanges(20, Instant.parse("2026-08-17T00:00:00Z"));
    changes.record("src/main/java/UserService.java", RecentChanges.OP_CREATE, "created");
    assertFalse(changes.isEmpty());
    assertEquals(1, changes.entries().size());
    RecentChange entry = changes.entries().get(0);
    assertEquals("src/main/java/UserService.java", entry.path());
    assertEquals(RecentChanges.OP_CREATE, entry.operation());
    assertEquals("created", entry.summary());
  }

  /** 直近の変更の把握で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void editCanBeRecorded() {
    RecentChanges changes = recentChanges(20, Instant.parse("2026-08-17T00:00:00Z"));
    changes.record("src/main/java/UserService.java", RecentChanges.OP_EDIT, "edited");
    RecentChange entry = changes.entries().get(0);
    assertEquals(RecentChanges.OP_EDIT, entry.operation());
    assertEquals("edited", entry.summary());
  }

  /** 直近の変更の把握で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void deleteCanBeRecorded() {
    RecentChanges changes = recentChanges(20, Instant.parse("2026-08-17T00:00:00Z"));
    changes.record("src/main/java/UserService.java", RecentChanges.OP_DELETE, "deleted");
    RecentChange entry = changes.entries().get(0);
    assertEquals(RecentChanges.OP_DELETE, entry.operation());
    assertEquals("deleted", entry.summary());
  }

  /** 直近の変更の把握で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void sameFileReEditOverwritesLatestEntry() {
    RecentChanges changes = recentChanges(20, Instant.parse("2026-08-17T00:00:00Z"));
    changes.record("src/UserService.java", RecentChanges.OP_EDIT, "first");
    changes.record("src/UserService.java", RecentChanges.OP_EDIT, "second");
    assertEquals(1, changes.entries().size());
    assertEquals("second", changes.entries().get(0).summary());
  }

  /** 直近の変更の把握で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void maxEntriesEvictsOldest() {
    RecentChanges changes = recentChanges(2, Instant.parse("2026-08-17T00:00:00Z"));
    changes.record("a", RecentChanges.OP_EDIT, "a");
    changes.record("b", RecentChanges.OP_EDIT, "b");
    changes.record("c", RecentChanges.OP_EDIT, "c");
    assertEquals(2, changes.entries().size());
    assertEquals("b", changes.entries().get(0).summary());
    assertEquals("c", changes.entries().get(1).summary());
  }

  /** 直近の変更の把握で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void resetClearsAllEntries() {
    RecentChanges changes = recentChanges(20, Instant.parse("2026-08-17T00:00:00Z"));
    changes.record("a", RecentChanges.OP_EDIT, "a");
    changes.reset();
    assertTrue(changes.isEmpty());
  }

  /** 直近の変更の把握で、状態の変化と結果を、通知・表示の契約に従って伝えることを確認する。 */
  @Test
  void renderForPromptContainsChanges() {
    RecentChanges changes = recentChanges(20, Instant.parse("2026-08-17T00:00:00Z"));
    changes.record("src/UserService.java", RecentChanges.OP_EDIT, "save() を Optional 化");
    String prompt = changes.renderForPrompt();
    assertTrue(prompt.contains("## Recent Changes"));
    assertTrue(prompt.contains("src/UserService.java"));
    assertTrue(prompt.contains("save() を Optional 化"));
  }

  /** 直近の変更の把握で、状態の変化と結果を、通知・表示の契約に従って伝えることを確認する。 */
  @Test
  void emptyRecentChangesRendersBlank() {
    RecentChanges changes = recentChanges(20, Instant.parse("2026-08-17T00:00:00Z"));
    assertEquals("", changes.renderForPrompt());
  }
}
