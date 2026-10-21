package dev.mikoto2000.rei.core.filesummary;

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

/** File Summary Cache がセッション（アプリケーション全体のシングルトン Bean）として保持されることを検証する。 */
@org.junit.jupiter.api.Tag("integration")
class FileSummaryCacheSessionTest {

  /** 検証に使うタイムゾーン。 */
  private static final ZoneId ZONE = ZoneId.of("Asia/Tokyo");

  /** ファイル内容の要約で、記録した情報を、定められた分類と集約の規則に従ってまとめることを確認する。 */
  @Test
  void providesFileSummaryCacheBean() {
    FileSummaryCacheConfiguration config = new FileSummaryCacheConfiguration();
    FileSummaryCache cache =
        config.fileSummaryCache(Clock.fixed(Instant.parse("2026-08-17T00:00:00Z"), ZONE));
    assertNotNull(cache);
    assertTrue(cache.isEmpty());
  }

  /** ファイル内容の要約で、記録した情報を、定められた分類と集約の規則に従ってまとめることを確認する。 */
  @Test
  void fileSummaryCacheIsSingletonAcrossContext() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.register(FileSummaryCacheConfiguration.class, TestConfiguration.class);
      context.refresh();
      FileSummaryCache first = context.getBean(FileSummaryCache.class);
      FileSummaryCache second = context.getBean(FileSummaryCache.class);
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
