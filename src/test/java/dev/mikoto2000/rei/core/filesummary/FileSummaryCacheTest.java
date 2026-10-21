package dev.mikoto2000.rei.core.filesummary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** ファイル要約キャッシュの結果と状態の契約を検証するテスト。 */
class FileSummaryCacheTest {

  /** 検証に使うタイムゾーン。 */
  private static final ZoneId ZONE = ZoneId.of("Asia/Tokyo");

  /** {@code cache} の条件を再現するための補助情報を返す。 */
  private FileSummaryCache cache(int maxEntries, Instant start) {
    return new FileSummaryCache(maxEntries, Clock.fixed(start, ZONE));
  }

  /** ファイル内容の要約で、記録した情報を、定められた分類と集約の規則に従ってまとめることを確認する。 */
  @Test
  void fileSummaryCanBeSaved() {
    FileSummaryCache cache = cache(20, Instant.parse("2026-08-17T00:00:00Z"));
    cache.save(
        new FileSummary(
            "src/UserService.java",
            "abc123",
            "User の作成・更新を担当",
            Instant.parse("2026-08-17T00:00:00Z").atZone(ZONE).toOffsetDateTime()));
    assertFalse(cache.isEmpty());
    assertEquals(1, cache.entries().size());
  }

  /** ファイル内容の要約で、検索条件に応じて、必要な候補と根拠を選ぶことを確認する。 */
  @Test
  void summaryCanBeRetrievedByPath() {
    FileSummaryCache cache = cache(20, Instant.parse("2026-08-17T00:00:00Z"));
    cache.save(
        new FileSummary(
            "src/UserService.java",
            "abc123",
            "User の作成・更新を担当",
            Instant.parse("2026-08-17T00:00:00Z").atZone(ZONE).toOffsetDateTime()));
    FileSummary found = cache.find("src/UserService.java").orElseThrow();
    assertEquals("abc123", found.version());
    assertEquals("User の作成・更新を担当", found.summary());
  }

  /** ファイル内容の要約で、記録した情報を、定められた分類と集約の規則に従ってまとめることを確認する。 */
  @Test
  void summaryIsUsableWhenVersionMatches() {
    FileSummaryCache cache = cache(20, Instant.parse("2026-08-17T00:00:00Z"));
    cache.save(
        new FileSummary(
            "src/UserService.java",
            "abc123",
            "User の作成・更新を担当",
            Instant.parse("2026-08-17T00:00:00Z").atZone(ZONE).toOffsetDateTime()));
    assertTrue(cache.isUsable("src/UserService.java", "abc123"));
  }

  /** ファイル内容の要約で、記録した情報を、定められた分類と集約の規則に従ってまとめることを確認する。 */
  @Test
  void summaryIsStaleWhenVersionDiffers() {
    FileSummaryCache cache = cache(20, Instant.parse("2026-08-17T00:00:00Z"));
    cache.save(
        new FileSummary(
            "src/UserService.java",
            "abc123",
            "User の作成・更新を担当",
            Instant.parse("2026-08-17T00:00:00Z").atZone(ZONE).toOffsetDateTime()));
    assertFalse(cache.isUsable("src/UserService.java", "def456"));
  }

  /** ファイル内容の要約で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void staleSummaryIsNotRenderedInPrompt() {
    FileSummaryCache cache = cache(20, Instant.parse("2026-08-17T00:00:00Z"));
    cache.save(
        new FileSummary(
            "src/UserService.java",
            "abc123",
            "User の作成・更新を担当",
            Instant.parse("2026-08-17T00:00:00Z").atZone(ZONE).toOffsetDateTime()));
    String prompt = cache.renderForPrompt(path -> "def456");
    assertFalse(prompt.contains("User の作成・更新を担当"));
  }

  /** ファイル内容の要約で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void editInvalidatesSummary() {
    FileSummaryCache cache = cache(20, Instant.parse("2026-08-17T00:00:00Z"));
    cache.save(
        new FileSummary(
            "src/UserService.java",
            "abc123",
            "User の作成・更新を担当",
            Instant.parse("2026-08-17T00:00:00Z").atZone(ZONE).toOffsetDateTime()));
    cache.invalidate("src/UserService.java");
    assertFalse(cache.find("src/UserService.java").isPresent());
  }

  /** ファイル内容の要約で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void maxEntriesEvictsOldest() {
    FileSummaryCache cache = cache(2, Instant.parse("2026-08-17T00:00:00Z"));
    cache.save(
        new FileSummary(
            "a", "v1", "a", Instant.parse("2026-08-17T00:00:00Z").atZone(ZONE).toOffsetDateTime()));
    cache.save(
        new FileSummary(
            "b", "v1", "b", Instant.parse("2026-08-17T00:00:00Z").atZone(ZONE).toOffsetDateTime()));
    cache.save(
        new FileSummary(
            "c", "v1", "c", Instant.parse("2026-08-17T00:00:00Z").atZone(ZONE).toOffsetDateTime()));
    assertEquals(2, cache.entries().size());
    assertFalse(cache.find("a").isPresent());
    assertTrue(cache.find("b").isPresent());
    assertTrue(cache.find("c").isPresent());
  }

  /** ファイル内容の要約で、状態の変化と結果を、通知・表示の契約に従って伝えることを確認する。 */
  @Test
  void emptyCacheRendersBlank() {
    FileSummaryCache cache = cache(20, Instant.parse("2026-08-17T00:00:00Z"));
    assertEquals("", cache.renderForPrompt(path -> "v1"));
  }

  /** ファイル内容の要約で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void missingFileIsNotUsable() {
    FileSummaryCache cache = cache(20, Instant.parse("2026-08-17T00:00:00Z"));
    cache.save(
        new FileSummary(
            "src/UserService.java",
            "abc123",
            "User の作成・更新を担当",
            Instant.parse("2026-08-17T00:00:00Z").atZone(ZONE).toOffsetDateTime()));
    assertFalse(cache.isUsable("src/UserService.java", null));
  }

  /** キャッシュの無効化を保存することを確認する。 */
  @Test
  void cachePublishesSaveInvalidateAndStaleSkippedEvents() {
    InMemoryAgentEventBus bus = new InMemoryAgentEventBus();
    List<AgentEvent> events = new ArrayList<>();
    bus.subscribe(events::add);
    Clock clock = Clock.fixed(Instant.parse("2026-08-17T00:00:00Z"), ZONE);
    FileSummaryCache cache = new FileSummaryCache(20, clock, new AgentEventFactory(clock), bus);

    cache.save(
        new FileSummary(
            "src/UserService.java",
            "abc123",
            "summary",
            Instant.parse("2026-08-17T00:00:00Z").atZone(ZONE).toOffsetDateTime()));
    cache.renderForPrompt(path -> "def456");
    cache.invalidate("src/UserService.java");

    assertEquals(AgentEventType.FILE_SUMMARY_SAVED, events.get(0).type());
    assertEquals(AgentEventType.FILE_SUMMARY_STALE_SKIPPED, events.get(1).type());
    assertEquals(AgentEventType.FILE_SUMMARY_INVALIDATED, events.get(2).type());
  }
}
