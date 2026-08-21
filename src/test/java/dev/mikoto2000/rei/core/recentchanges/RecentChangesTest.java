package dev.mikoto2000.rei.core.recentchanges;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

class RecentChangesTest {

  private static final ZoneId ZONE = ZoneId.of("Asia/Tokyo");

  private RecentChanges recentChanges(int maxEntries, Instant start) {
    return new RecentChanges(maxEntries, Clock.fixed(start, ZONE));
  }

  @Test
  void newRecentChangesIsEmpty() {
    RecentChanges changes = recentChanges(20, Instant.parse("2026-08-17T00:00:00Z"));
    assertTrue(changes.isEmpty());
    assertEquals(0, changes.entries().size());
  }

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

  @Test
  void editCanBeRecorded() {
    RecentChanges changes = recentChanges(20, Instant.parse("2026-08-17T00:00:00Z"));
    changes.record("src/main/java/UserService.java", RecentChanges.OP_EDIT, "edited");
    RecentChange entry = changes.entries().get(0);
    assertEquals(RecentChanges.OP_EDIT, entry.operation());
    assertEquals("edited", entry.summary());
  }

  @Test
  void deleteCanBeRecorded() {
    RecentChanges changes = recentChanges(20, Instant.parse("2026-08-17T00:00:00Z"));
    changes.record("src/main/java/UserService.java", RecentChanges.OP_DELETE, "deleted");
    RecentChange entry = changes.entries().get(0);
    assertEquals(RecentChanges.OP_DELETE, entry.operation());
    assertEquals("deleted", entry.summary());
  }

  @Test
  void sameFileReEditOverwritesLatestEntry() {
    RecentChanges changes = recentChanges(20, Instant.parse("2026-08-17T00:00:00Z"));
    changes.record("src/UserService.java", RecentChanges.OP_EDIT, "first");
    changes.record("src/UserService.java", RecentChanges.OP_EDIT, "second");
    assertEquals(1, changes.entries().size());
    assertEquals("second", changes.entries().get(0).summary());
  }

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
}