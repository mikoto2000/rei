package dev.mikoto2000.rei.core.filesummary;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** File Summary Cache の Bean 定義。 */
@Configuration(proxyBeanMethods = false)
public class FileSummaryCacheConfiguration {

  /** ファイル内容の要約で用いるファイル要約キャッシュを返す。 */
  @org.springframework.context.annotation.Scope(
      value = "reiProject",
      proxyMode = org.springframework.context.annotation.ScopedProxyMode.TARGET_CLASS)
  @Bean
  public FileSummaryCache fileSummaryCache(Clock clock) {
    return new FileSummaryCache(FileSummaryCache.DEFAULT_MAX_ENTRIES, clock);
  }
}
