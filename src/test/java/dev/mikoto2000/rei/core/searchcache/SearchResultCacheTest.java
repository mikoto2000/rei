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

class SearchResultCacheTest {

  private static final ZoneId ZONE = ZoneId.of("Asia/Tokyo");

  private SearchResultCache cache(Instant start) {
    return new SearchResultCache(Duration.ofSeconds(60), 100, Clock.fixed(start, ZONE));
  }

  @Test
  void searchResultCanBeSaved() {
    SearchResultCache cache = cache(Instant.parse("2026-08-17T00:00:00Z"));
    SearchCacheKey key = new SearchCacheKey("grepMultiQuery", "pattern=UserService");
    List<String> result = List.of("docs/UserService.java:1:class UserService");

    cache.put(key, result);

    assertFalse(cache.isEmpty());
    assertEquals(1, cache.size());
  }

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

  @Test
  void differentQueryIsDifferentKey() {
    SearchResultCache cache = cache(Instant.parse("2026-08-17T00:00:00Z"));
    SearchCacheKey keyA = new SearchCacheKey("grepMultiQuery", "pattern=UserService");
    SearchCacheKey keyB = new SearchCacheKey("grepMultiQuery", "pattern=TaskState");
    cache.put(keyA, List.of("docs/UserService.java"));

    assertFalse(cache.get(keyB).isPresent());
  }

  @Test
  void differentPathIsDifferentKey() {
    SearchResultCache cache = cache(Instant.parse("2026-08-17T00:00:00Z"));
    SearchCacheKey keyA = new SearchCacheKey("grepMultiQuery", "pattern=UserService|path=src");
    SearchCacheKey keyB = new SearchCacheKey("grepMultiQuery", "pattern=UserService|path=docs");
    cache.put(keyA, List.of("src/UserService.java"));

    assertFalse(cache.get(keyB).isPresent());
  }

  @Test
  void hitWithinTtl() {
    SearchResultCache cache = cache(Instant.parse("2026-08-17T00:00:00Z"));
    SearchCacheKey key = new SearchCacheKey("grepMultiQuery", "pattern=UserService");
    cache.put(key, List.of("docs/UserService.java"));

    assertTrue(cache.get(key).isPresent());
  }

  @Test
  void missAfterTtlExpires() {
    SearchResultCache cache = cache(Instant.parse("2026-08-17T00:00:00Z"));
    SearchCacheKey key = new SearchCacheKey("grepMultiQuery", "pattern=UserService");
    cache.put(key, List.of("docs/UserService.java"));

    SearchResultCache later = new SearchResultCache(Duration.ofSeconds(60), 100,
        Clock.fixed(Instant.parse("2026-08-17T00:02:00Z"), ZONE));

    assertFalse(later.get(key).isPresent());
  }
}