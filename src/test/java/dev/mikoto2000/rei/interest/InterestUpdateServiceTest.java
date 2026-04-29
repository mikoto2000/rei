package dev.mikoto2000.rei.interest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class InterestUpdateServiceTest {

  @TempDir
  java.nio.file.Path tempDir;

  InterestUpdateService service;

  @BeforeEach
  void setUp() {
    service = new InterestUpdateService(
        new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("test.db")));
  }

  // --- existsByTopicWithinHours ---

  @Test
  void existsByTopicWithinHours_returnsTrueWhenRecordExistsWithinLimit() {
    // 1時間前に保存されたレコード（制限: 2時間）
    saveWithCreatedAt("Neovim", "query-1", OffsetDateTime.now(ZoneOffset.UTC).minusHours(1));
    assertTrue(service.existsByTopicWithinHours("Neovim", 2));
  }

  @Test
  void existsByTopicWithinHours_returnsFalseWhenRecordExistsOutsideLimit() {
    // 3時間前に保存されたレコード（制限: 2時間）
    saveWithCreatedAt("Neovim", "query-2", OffsetDateTime.now(ZoneOffset.UTC).minusHours(3));
    assertFalse(service.existsByTopicWithinHours("Neovim", 2));
  }

  @Test
  void existsByTopicWithinHours_returnsFalseWhenNoRecord() {
    assertFalse(service.existsByTopicWithinHours("NonExistentTopic", 24));
  }

  @Test
  void existsByTopicWithinHours_returnsFalseForDifferentTopic() {
    saveWithCreatedAt("Neovim", "query-3", OffsetDateTime.now(ZoneOffset.UTC).minusHours(1));
    assertFalse(service.existsByTopicWithinHours("Vim", 2));
  }

  // ヘルパー: 任意の createdAt で保存する
  private void saveWithCreatedAt(String topic, String searchQuery, OffsetDateTime createdAt) {
    service.saveWithCreatedAt(topic, "reason", searchQuery, "summary", java.util.List.of(), createdAt);
  }

  // --- listRecentSearchQueries ---

  @Test
  void listRecentSearchQueries_returnsQueriesWithinDays() {
    saveWithCreatedAt("Topic A", "query-within", OffsetDateTime.now(ZoneOffset.UTC).minusDays(1));
    saveWithCreatedAt("Topic B", "query-outside", OffsetDateTime.now(ZoneOffset.UTC).minusDays(10));

    java.util.List<String> result = service.listRecentSearchQueries(7);

    assertTrue(result.contains("query-within"));
    assertFalse(result.contains("query-outside"));
  }

  @Test
  void listRecentSearchQueries_deduplicatesQueries() {
    // 同一クエリを 2 回保存しようとしても UNIQUE 制約で 1 件になる
    saveWithCreatedAt("Topic A", "unique-query", OffsetDateTime.now(ZoneOffset.UTC).minusHours(1));

    java.util.List<String> result = service.listRecentSearchQueries(7);

    assertEquals(1, result.stream().filter(q -> q.equals("unique-query")).count());
  }

  @Test
  void listRecentSearchQueries_returnsEmptyWhenNoRecentQueries() {
    saveWithCreatedAt("Topic A", "old-query", OffsetDateTime.now(ZoneOffset.UTC).minusDays(30));

    java.util.List<String> result = service.listRecentSearchQueries(7);

    assertFalse(result.contains("old-query"));
  }
}

