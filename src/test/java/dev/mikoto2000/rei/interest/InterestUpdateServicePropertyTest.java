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
 * Feature: interest-discovery Property 2: 過去クエリ一覧は指定日数以内のものだけを返す Property 3（部分）:
 * existsByTopicWithinHours の境界値
 *
 * <p>jqwik は JUnit 6 と互換性がないため、JUnit 6 の @ParameterizedTest で代替実装する。 各テストは 30 パターン（days: 1〜30）または
 * 30 パターン（hours: 1〜30）で実行する。
 */
@org.junit.jupiter.api.Tag("integration")
class InterestUpdateServicePropertyTest {

  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir java.nio.file.Path tempDir;

  /** 検証に使うcounter。 */
  private static final AtomicInteger counter = new AtomicInteger(0);

  /** 指定された条件を再現するための検証対象や入力情報を作成する。 */
  private InterestUpdateService createService(String dbName) {
    return new InterestUpdateService(
        new org.springframework.jdbc.datasource.DriverManagerDataSource(
            "jdbc:sqlite:" + tempDir.resolve(dbName)));
  }

  /** {@code uniqueQuery} の条件を再現するための補助情報を返す。 */
  private String uniqueQuery(String prefix) {
    return prefix + "-" + counter.incrementAndGet();
  }

  // --- Property 2: 過去クエリ一覧は指定日数以内のものだけを返す ---

  /** {@code daysRange} の条件を再現するための補助情報を返す。 */
  static Stream<Integer> daysRange() {
    return IntStream.rangeClosed(1, 30).boxed();
  }

  /** 関心の管理で、検索条件に応じて、必要な候補と根拠を選ぶことを確認する。 */
  @ParameterizedTest(name = "days={0}")
  @MethodSource("daysRange")
  @Tag("interest-discovery-property-2-listRecentSearchQueries")
  void listRecentSearchQueriesReturnsOnlyWithinDays(int days) {
    InterestUpdateService service =
        createService("prop2-" + days + "-" + counter.incrementAndGet() + ".db");

    String withinQuery = uniqueQuery("within");
    String outsideQuery = uniqueQuery("outside");

    // days 日以内のレコード
    service.saveWithCreatedAt(
        "TopicA",
        "reason",
        withinQuery,
        "summary",
        List.of(),
        OffsetDateTime.now(ZoneOffset.UTC).minusDays(days - 1));
    // days 日超のレコード
    service.saveWithCreatedAt(
        "TopicB",
        "reason",
        outsideQuery,
        "summary",
        List.of(),
        OffsetDateTime.now(ZoneOffset.UTC).minusDays(days + 1));

    List<String> result = service.listRecentSearchQueries(days);

    assertTrue(result.contains(withinQuery), "days=" + days + " 以内のクエリが含まれるべき");
    assertFalse(result.contains(outsideQuery), "days=" + days + " 超のクエリは含まれないべき");
  }

  // --- Property 3（部分）: existsByTopicWithinHours の境界値 ---

  /** {@code hoursRange} の条件を再現するための補助情報を返す。 */
  static Stream<Integer> hoursRange() {
    return IntStream.rangeClosed(1, 30).boxed();
  }

  /** 存在別話題範囲内時間数について、有効を返すことを確認する。 */
  @ParameterizedTest(name = "hours={0}")
  @MethodSource("hoursRange")
  @Tag("interest-discovery-property-3-existsByTopicWithinHours-true")
  void existsByTopicWithinHoursReturnsTrueWhenWithinLimit(int hours) {
    InterestUpdateService service =
        createService("prop3t-" + hours + "-" + counter.incrementAndGet() + ".db");
    String topic = "Topic-" + hours;
    String query = uniqueQuery("q");

    // hours 時間以内に保存
    service.saveWithCreatedAt(
        topic,
        "reason",
        query,
        "summary",
        List.of(),
        OffsetDateTime.now(ZoneOffset.UTC).minusHours(hours - 1));

    assertTrue(
        service.existsByTopicWithinHours(topic, hours), "hours=" + hours + " 以内のトピックは true を返すべき");
  }

  /** 存在別話題範囲内時間数について、無効を返すことを確認する。 */
  @ParameterizedTest(name = "hours={0}")
  @MethodSource("hoursRange")
  @Tag("interest-discovery-property-3-existsByTopicWithinHours-false")
  void existsByTopicWithinHoursReturnsFalseWhenOutsideLimit(int hours) {
    InterestUpdateService service =
        createService("prop3f-" + hours + "-" + counter.incrementAndGet() + ".db");
    String topic = "Topic-" + hours;
    String query = uniqueQuery("q");

    // hours 時間超に保存
    service.saveWithCreatedAt(
        topic,
        "reason",
        query,
        "summary",
        List.of(),
        OffsetDateTime.now(ZoneOffset.UTC).minusHours(hours + 1));

    assertFalse(
        service.existsByTopicWithinHours(topic, hours), "hours=" + hours + " 超のトピックは false を返すべき");
  }
}
