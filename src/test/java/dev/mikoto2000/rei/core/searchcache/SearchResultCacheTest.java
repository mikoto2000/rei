package dev.mikoto2000.rei.core.searchcache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 検索結果キャッシュの結果と状態の契約を検証するテスト。 */
class SearchResultCacheTest {

  /** 検証に使うタイムゾーン。 */
  private static final ZoneId ZONE = ZoneId.of("Asia/Tokyo");

  /** {@code cache} の条件を再現するための補助情報を返す。 */
  private SearchResultCache cache(Instant start) {
    return new SearchResultCache(Duration.ofSeconds(60), 100, Clock.fixed(start, ZONE));
  }

  /** 検索結果の再利用で、検索条件に応じて、必要な候補と根拠を選ぶことを確認する。 */
  @Test
  void searchResultCanBeSaved() {
    SearchResultCache cache = cache(Instant.parse("2026-08-17T00:00:00Z"));
    SearchCacheKey key = new SearchCacheKey("grepMultiQuery", "pattern=UserService");
    List<String> result = List.of("docs/UserService.java:1:class UserService");

    cache.put(key, result);

    assertFalse(cache.isEmpty());
    assertEquals(1, cache.size());
  }

  /** 検索結果の再利用で、検索条件に応じて、必要な候補と根拠を選ぶことを確認する。 */
  @Test
  void resultCanBeRetrievedBySameKey() {
    SearchResultCache cache = cache(Instant.parse("2026-08-17T00:00:00Z"));
    SearchCacheKey key = new SearchCacheKey("grepMultiQuery", "pattern=UserService");
    List<String> result = List.of("docs/UserService.java:1:class UserService");
    cache.put(key, result);

    Optional<Object> found = cache.get(key);

    assertTrue(found.isPresent());
    assertEquals(result, found.get());
  }

  /** 検索結果の再利用で、検索条件に応じて、必要な候補と根拠を選ぶことを確認する。 */
  @Test
  void differentQueryIsDifferentKey() {
    SearchResultCache cache = cache(Instant.parse("2026-08-17T00:00:00Z"));
    SearchCacheKey keyA = new SearchCacheKey("grepMultiQuery", "pattern=UserService");
    SearchCacheKey keyB = new SearchCacheKey("grepMultiQuery", "pattern=TaskState");
    cache.put(keyA, List.of("docs/UserService.java"));

    assertFalse(cache.get(keyB).isPresent());
  }

  /** 検索結果の再利用で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void differentPathIsDifferentKey() {
    SearchResultCache cache = cache(Instant.parse("2026-08-17T00:00:00Z"));
    SearchCacheKey keyA = new SearchCacheKey("grepMultiQuery", "pattern=UserService|path=src");
    SearchCacheKey keyB = new SearchCacheKey("grepMultiQuery", "pattern=UserService|path=docs");
    cache.put(keyA, List.of("src/UserService.java"));

    assertFalse(cache.get(keyB).isPresent());
  }

  /** 検索結果の再利用で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void hitWithinTtl() {
    SearchResultCache cache = cache(Instant.parse("2026-08-17T00:00:00Z"));
    SearchCacheKey key = new SearchCacheKey("grepMultiQuery", "pattern=UserService");
    cache.put(key, List.of("docs/UserService.java"));

    assertTrue(cache.get(key).isPresent());
  }

  /** 検索結果の再利用で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void missAfterTtlExpires() {
    var clock =
        new dev.mikoto2000.rei.testsupport.AdjustableClock(Instant.parse("2026-08-17T00:00:00Z"));
    SearchResultCache cache = new SearchResultCache(Duration.ofSeconds(60), 100, clock);
    SearchCacheKey key = new SearchCacheKey("grepMultiQuery", "pattern=UserService");
    cache.put(key, List.of("docs/UserService.java"));
    clock.advance(Duration.ofSeconds(60));
    assertFalse(cache.get(key).isPresent());
  }

  /** 検索結果の再利用で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void failedResultIsNotCached() {
    SearchResultCache cache = cache(Instant.parse("2026-08-17T00:00:00Z"));
    SearchCacheKey key = new SearchCacheKey("grepMultiQuery", "pattern=UserService");

    // 失敗結果は保存しない（put しない）ため、取得できない
    assertFalse(cache.get(key).isPresent());
  }

  /** 保持内容の消去について、全対象項目一覧を取り除くことを確認する。 */
  @Test
  void clearRemovesAllEntries() {
    SearchResultCache cache = cache(Instant.parse("2026-08-17T00:00:00Z"));
    cache.put(new SearchCacheKey("grepMultiQuery", "pattern=A"), List.of("a"));
    cache.put(new SearchCacheKey("grepMultiQuery", "pattern=B"), List.of("b"));

    cache.clear();

    assertTrue(cache.isEmpty());
    assertEquals(0, cache.size());
  }

  /** 検索結果の再利用で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void maxEntriesEvictsOldest() {
    SearchResultCache cache =
        new SearchResultCache(
            Duration.ofSeconds(60), 2, Clock.fixed(Instant.parse("2026-08-17T00:00:00Z"), ZONE));
    cache.put(new SearchCacheKey("grepMultiQuery", "pattern=A"), List.of("a"));
    cache.put(new SearchCacheKey("grepMultiQuery", "pattern=B"), List.of("b"));
    cache.put(new SearchCacheKey("grepMultiQuery", "pattern=C"), List.of("c"));

    assertEquals(2, cache.size());
    assertFalse(cache.get(new SearchCacheKey("grepMultiQuery", "pattern=A")).isPresent());
    assertTrue(cache.get(new SearchCacheKey("grepMultiQuery", "pattern=B")).isPresent());
    assertTrue(cache.get(new SearchCacheKey("grepMultiQuery", "pattern=C")).isPresent());
  }
}
