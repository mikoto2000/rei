package dev.mikoto2000.rei.core.searchcache;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Search Result Cache がセッション（アプリケーション全体のシングルトン Bean）として保持されることを検証する。
 */
class SearchResultCacheSessionTest {

  private static final ZoneId ZONE = ZoneId.of("Asia/Tokyo");

  @Test
  void providesSearchResultCacheBean() {
    SearchResultCacheConfiguration config = new SearchResultCacheConfiguration();
    SearchResultCache cache = config.searchResultCache(Clock.fixed(Instant.parse("2026-08-17T00:00:00Z"), ZONE));
    assertNotNull(cache);
    assertTrue(cache.isEmpty());
  }

  @Test
  void searchResultCacheIsSingletonAcrossContext() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.register(SearchResultCacheConfiguration.class, TestConfiguration.class);
      context.refresh();
      SearchResultCache first = context.getBean(SearchResultCache.class);
      SearchResultCache second = context.getBean(SearchResultCache.class);
      assertSame(first, second);
    }
  }

  @Configuration
  static class TestConfiguration {

    @Bean
    Clock clock() {
      return Clock.fixed(Instant.parse("2026-08-17T00:00:00Z"), ZONE);
    }
  }
}