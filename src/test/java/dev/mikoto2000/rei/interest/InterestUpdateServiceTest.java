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

/** 関心更新サービスの結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class InterestUpdateServiceTest {

  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir java.nio.file.Path tempDir;

  /** 検証に使うサービス。 */
  InterestUpdateService service;

  /** 各検証を独立した状態で始められるよう、依存先と検証データを準備する。 */
  @BeforeEach
  void setUp() {
    service =
        new InterestUpdateService(
            new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("test.db")));
  }

  // --- existsByTopicWithinHours ---

  /** 存在別話題範囲内時間数について、有効を返すことを確認する。 */
  @Test
  void existsByTopicWithinHours_returnsTrueWhenRecordExistsWithinLimit() {
    // 1時間前に保存されたレコード（制限: 2時間）
    saveWithCreatedAt("Neovim", "query-1", OffsetDateTime.now(ZoneOffset.UTC).minusHours(1));
    assertTrue(service.existsByTopicWithinHours("Neovim", 2));
  }

  /** 存在別話題範囲内時間数について、無効を返すことを確認する。 */
  @Test
  void existsByTopicWithinHours_returnsFalseWhenRecordExistsOutsideLimit() {
    // 3時間前に保存されたレコード（制限: 2時間）
    saveWithCreatedAt("Neovim", "query-2", OffsetDateTime.now(ZoneOffset.UTC).minusHours(3));
    assertFalse(service.existsByTopicWithinHours("Neovim", 2));
  }

  /** 存在別話題範囲内時間数について、無効を返すことを確認する。 */
  @Test
  void existsByTopicWithinHours_returnsFalseWhenNoRecord() {
    assertFalse(service.existsByTopicWithinHours("NonExistentTopic", 24));
  }

  /** 存在別話題範囲内時間数について、無効を返すことを確認する。 */
  @Test
  void existsByTopicWithinHours_returnsFalseForDifferentTopic() {
    saveWithCreatedAt("Neovim", "query-3", OffsetDateTime.now(ZoneOffset.UTC).minusHours(1));
    assertFalse(service.existsByTopicWithinHours("Vim", 2));
  }

  // ヘルパー: 任意の createdAt で保存する
  /** 検証用に、後から参照できるよう、付き作成日時を保存する。 */
  private void saveWithCreatedAt(String topic, String searchQuery, OffsetDateTime createdAt) {
    service.saveWithCreatedAt(
        topic, "reason", searchQuery, "summary", java.util.List.of(), createdAt);
  }

  // --- listRecentSearchQueries ---

  /** 一覧直近検索検索条件一覧について、検索条件一覧範囲内日数を返すことを確認する。 */
  @Test
  void listRecentSearchQueries_returnsQueriesWithinDays() {
    saveWithCreatedAt("Topic A", "query-within", OffsetDateTime.now(ZoneOffset.UTC).minusDays(1));
    saveWithCreatedAt("Topic B", "query-outside", OffsetDateTime.now(ZoneOffset.UTC).minusDays(10));

    java.util.List<String> result = service.listRecentSearchQueries(7);

    assertTrue(result.contains("query-within"));
    assertFalse(result.contains("query-outside"));
  }

  /** 関心の管理で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void listRecentSearchQueries_deduplicatesQueries() {
    // 同一クエリを 2 回保存しようとしても UNIQUE 制約で 1 件になる
    saveWithCreatedAt("Topic A", "unique-query", OffsetDateTime.now(ZoneOffset.UTC).minusHours(1));

    java.util.List<String> result = service.listRecentSearchQueries(7);

    assertEquals(1, result.stream().filter(q -> q.equals("unique-query")).count());
  }

  /** 一覧直近検索検索条件一覧について、空のを返すことを確認する。 */
  @Test
  void listRecentSearchQueries_returnsEmptyWhenNoRecentQueries() {
    saveWithCreatedAt("Topic A", "old-query", OffsetDateTime.now(ZoneOffset.UTC).minusDays(30));

    java.util.List<String> result = service.listRecentSearchQueries(7);

    assertFalse(result.contains("old-query"));
  }
}
