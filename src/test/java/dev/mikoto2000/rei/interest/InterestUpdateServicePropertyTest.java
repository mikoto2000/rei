package dev.mikoto2000.rei.interest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Feature: interest-discovery
 * Property 2: 過去クエリ一覧は指定日数以内のものだけを返す
 * Property 3（部分）: existsByTopicWithinHours の境界値
 *
 * jqwik は JUnit 6 と互換性がないため、JUnit 6 の @ParameterizedTest で代替実装する。
 * 各テストは 30 パターン（days: 1〜30）または 30 パターン（hours: 1〜30）で実行する。
 */
class InterestUpdateServicePropertyTest {

  @TempDir
  java.nio.file.Path tempDir;

  private static final AtomicInteger counter = new AtomicInteger(0);

  private InterestUpdateService createService(String dbName) {
    return new InterestUpdateService(
        new org.springframework.jdbc.datasource.DriverManagerDataSource(
            "jdbc:sqlite:" + tempDir.resolve(dbName)));
  }

  private String uniqueQuery(String prefix) {
    return prefix + "-" + counter.incrementAndGet();
  }

  // --- Property 2: 過去クエリ一覧は指定日数以内のものだけを返す ---

  static Stream<Integer> daysRange() {
    return IntStream.rangeClosed(1, 30).boxed();
  }

  @ParameterizedTest(name = "days={0}")
  @MethodSource("daysRange")
  @Tag("interest-discovery-property-2-listRecentSearchQueries")
  void listRecentSearchQueriesReturnsOnlyWithinDays(int days) {
    InterestUpdateService service = createService("prop2-" + days + "-" + counter.incrementAndGet() + ".db");

    String withinQuery = uniqueQuery("within");
    String outsideQuery = uniqueQuery("outside");

    // days 日以内のレコード
    service.saveWithCreatedAt("TopicA", "reason", withinQuery, "summary", List.of(),
        OffsetDateTime.now(ZoneOffset.UTC).minusDays(days - 1));
    // days 日超のレコード
    service.saveWithCreatedAt("TopicB", "reason", outsideQuery, "summary", List.of(),
        OffsetDateTime.now(ZoneOffset.UTC).minusDays(days + 1));

    List<String> result = service.listRecentSearchQueries(days);

    assertTrue(result.contains(withinQuery),
        "days=" + days + " 以内のクエリが含まれるべき");
    assertFalse(result.contains(outsideQuery),
        "days=" + days + " 超のクエリは含まれないべき");
  }

  // --- Property 3（部分）: existsByTopicWithinHours の境界値 ---

  static Stream<Integer> hoursRange() {
    return IntStream.rangeClosed(1, 30).boxed();
  }

  @ParameterizedTest(name = "hours={0}")
  @MethodSource("hoursRange")
  @Tag("interest-discovery-property-3-existsByTopicWithinHours-true")
  void existsByTopicWithinHoursReturnsTrueWhenWithinLimit(int hours) {
    InterestUpdateService service = createService("prop3t-" + hours + "-" + counter.incrementAndGet() + ".db");
    String topic = "Topic-" + hours;
    String query = uniqueQuery("q");

    // hours 時間以内に保存
    service.saveWithCreatedAt(topic, "reason", query, "summary", List.of(),
        OffsetDateTime.now(ZoneOffset.UTC).minusHours(hours - 1));

    assertTrue(service.existsByTopicWithinHours(topic, hours),
        "hours=" + hours + " 以内のトピックは true を返すべき");
  }

  @ParameterizedTest(name = "hours={0}")
  @MethodSource("hoursRange")
  @Tag("interest-discovery-property-3-existsByTopicWithinHours-false")
  void existsByTopicWithinHoursReturnsFalseWhenOutsideLimit(int hours) {
    InterestUpdateService service = createService("prop3f-" + hours + "-" + counter.incrementAndGet() + ".db");
    String topic = "Topic-" + hours;
    String query = uniqueQuery("q");

    // hours 時間超に保存
    service.saveWithCreatedAt(topic, "reason", query, "summary", List.of(),
        OffsetDateTime.now(ZoneOffset.UTC).minusHours(hours + 1));

    assertFalse(service.existsByTopicWithinHours(topic, hours),
        "hours=" + hours + " 超のトピックは false を返すべき");
  }
}
