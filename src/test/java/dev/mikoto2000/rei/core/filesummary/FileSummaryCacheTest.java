package dev.mikoto2000.rei.core.filesummary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

class FileSummaryCacheTest {

  private static final ZoneId ZONE = ZoneId.of("Asia/Tokyo");

  private FileSummaryCache cache(int maxEntries, Instant start) {
    return new FileSummaryCache(maxEntries, Clock.fixed(start, ZONE));
  }

  @Test
  void fileSummaryCanBeSaved() {
    FileSummaryCache cache = cache(20, Instant.parse("2026-08-17T00:00:00Z"));
    cache.save(new FileSummary("src/UserService.java", "abc123", "User の作成・更新を担当",
        Instant.parse("2026-08-17T00:00:00Z").atZone(ZONE).toOffsetDateTime()));
    assertFalse(cache.isEmpty());
    assertEquals(1, cache.entries().size());
  }

  @Test
  void summaryCanBeRetrievedByPath() {
    FileSummaryCache cache = cache(20, Instant.parse("2026-08-17T00:00:00Z"));
    cache.save(new FileSummary("src/UserService.java", "abc123", "User の作成・更新を担当",
        Instant.parse("2026-08-17T00:00:00Z").atZone(ZONE).toOffsetDateTime()));
    FileSummary found = cache.find("src/UserService.java").orElseThrow();
    assertEquals("abc123", found.version());
    assertEquals("User の作成・更新を担当", found.summary());
  }

  @Test
  void summaryIsUsableWhenVersionMatches() {
    FileSummaryCache cache = cache(20, Instant.parse("2026-08-17T00:00:00Z"));
    cache.save(new FileSummary("src/UserService.java", "abc123", "User の作成・更新を担当",
        Instant.parse("2026-08-17T00:00:00Z").atZone(ZONE).toOffsetDateTime()));
    assertTrue(cache.isUsable("src/UserService.java", "abc123"));
  }

  @Test
  void summaryIsStaleWhenVersionDiffers() {
    FileSummaryCache cache = cache(20, Instant.parse("2026-08-17T00:00:00Z"));
    cache.save(new FileSummary("src/UserService.java", "abc123", "User の作成・更新を担当",
        Instant.parse("2026-08-17T00:00:00Z").atZone(ZONE).toOffsetDateTime()));
    assertFalse(cache.isUsable("src/UserService.java", "def456"));
  }

  @Test
  void staleSummaryIsNotRenderedInPrompt() {
    FileSummaryCache cache = cache(20, Instant.parse("2026-08-17T00:00:00Z"));
    cache.save(new FileSummary("src/UserService.java", "abc123", "User の作成・更新を担当",
        Instant.parse("2026-08-17T00:00:00Z").atZone(ZONE).toOffsetDateTime()));
    String prompt = cache.renderForPrompt(path -> "def456");
    assertFalse(prompt.contains("User の作成・更新を担当"));
  }

  @Test
  void editInvalidatesSummary() {
    FileSummaryCache cache = cache(20, Instant.parse("2026-08-17T00:00:00Z"));
    cache.save(new FileSummary("src/UserService.java", "abc123", "User の作成・更新を担当",
        Instant.parse("2026-08-17T00:00:00Z").atZone(ZONE).toOffsetDateTime()));
    cache.invalidate("src/UserService.java");
    assertFalse(cache.find("src/UserService.java").isPresent());
  }

  @Test
  void maxEntriesEvictsOldest() {
    FileSummaryCache cache = cache(2, Instant.parse("2026-08-17T00:00:00Z"));
    cache.save(new FileSummary("a", "v1", "a", Instant.parse("2026-08-17T00:00:00Z").atZone(ZONE).toOffsetDateTime()));
    cache.save(new FileSummary("b", "v1", "b", Instant.parse("2026-08-17T00:00:00Z").atZone(ZONE).toOffsetDateTime()));
    cache.save(new FileSummary("c", "v1", "c", Instant.parse("2026-08-17T00:00:00Z").atZone(ZONE).toOffsetDateTime()));
    assertEquals(2, cache.entries().size());
    assertFalse(cache.find("a").isPresent());
    assertTrue(cache.find("b").isPresent());
    assertTrue(cache.find("c").isPresent());
  }
}
