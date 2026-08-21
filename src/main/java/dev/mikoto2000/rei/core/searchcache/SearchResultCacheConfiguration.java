package dev.mikoto2000.rei.core.searchcache;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Search Result Cache の Bean 定義。
 */
@Configuration(proxyBeanMethods = false)
public class SearchResultCacheConfiguration {

  @Bean
  public SearchResultCache searchResultCache(Clock clock) {
    return new SearchResultCache(SearchResultCache.DEFAULT_TTL, SearchResultCache.DEFAULT_MAX_ENTRIES, clock);
  }
}