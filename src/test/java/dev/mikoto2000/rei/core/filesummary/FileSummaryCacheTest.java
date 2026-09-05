package dev.mikoto2000.rei.core.filesummary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;

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

  @Test
  void emptyCacheRendersBlank() {
    FileSummaryCache cache = cache(20, Instant.parse("2026-08-17T00:00:00Z"));
    assertEquals("", cache.renderForPrompt(path -> "v1"));
  }

  @Test
  void missingFileIsNotUsable() {
    FileSummaryCache cache = cache(20, Instant.parse("2026-08-17T00:00:00Z"));
    cache.save(new FileSummary("src/UserService.java", "abc123", "User の作成・更新を担当",
        Instant.parse("2026-08-17T00:00:00Z").atZone(ZONE).toOffsetDateTime()));
    assertFalse(cache.isUsable("src/UserService.java", null));
  }

  @Test
  void cachePublishesSaveInvalidateAndStaleSkippedEvents() {
    InMemoryAgentEventBus bus = new InMemoryAgentEventBus();
    List<AgentEvent> events = new ArrayList<>();
    bus.subscribe(events::add);
    Clock clock = Clock.fixed(Instant.parse("2026-08-17T00:00:00Z"), ZONE);
    FileSummaryCache cache = new FileSummaryCache(20, clock, new AgentEventFactory(clock), bus);

    cache.save(new FileSummary("src/UserService.java", "abc123", "summary",
        Instant.parse("2026-08-17T00:00:00Z").atZone(ZONE).toOffsetDateTime()));
    cache.renderForPrompt(path -> "def456");
    cache.invalidate("src/UserService.java");

    assertEquals(AgentEventType.FILE_SUMMARY_SAVED, events.get(0).type());
    assertEquals(AgentEventType.FILE_SUMMARY_STALE_SKIPPED, events.get(1).type());
    assertEquals(AgentEventType.FILE_SUMMARY_INVALIDATED, events.get(2).type());
  }
}
