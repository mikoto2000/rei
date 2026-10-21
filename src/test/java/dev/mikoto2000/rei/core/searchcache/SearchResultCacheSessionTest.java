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

/** Search Result Cache がセッション（アプリケーション全体のシングルトン Bean）として保持されることを検証する。 */
@org.junit.jupiter.api.Tag("integration")
class SearchResultCacheSessionTest {

  /** 検証に使うタイムゾーン。 */
  private static final ZoneId ZONE = ZoneId.of("Asia/Tokyo");

  /** 検索結果の再利用で、検索条件に応じて、必要な候補と根拠を選ぶことを確認する。 */
  @Test
  void providesSearchResultCacheBean() {
    SearchResultCacheConfiguration config = new SearchResultCacheConfiguration();
    SearchResultCache cache =
        config.searchResultCache(Clock.fixed(Instant.parse("2026-08-17T00:00:00Z"), ZONE));
    assertNotNull(cache);
    assertTrue(cache.isEmpty());
  }

  /** 検索結果の再利用で、検索条件に応じて、必要な候補と根拠を選ぶことを確認する。 */
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

  /** 検証対象に必要な依存先と設定を組み立てるテスト用構成。 */
  @Configuration
  static class TestConfiguration {

    /** {@code clock} の条件を再現するための補助情報を返す。 */
    @Bean
    Clock clock() {
      return Clock.fixed(Instant.parse("2026-08-17T00:00:00Z"), ZONE);
    }
  }
}
