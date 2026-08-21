package dev.mikoto2000.rei.core.recentchanges;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}