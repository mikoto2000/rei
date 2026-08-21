package dev.mikoto2000.rei.core.recentchanges;

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
 * Recent Changes がセッション（アプリケーション全体のシングルトン Bean）として保持されることを検証する。
 */
class RecentChangesSessionTest {

  private static final ZoneId ZONE = ZoneId.of("Asia/Tokyo");

  @Test
  void providesRecentChangesBean() {
    RecentChangesConfiguration config = new RecentChangesConfiguration();
    RecentChanges changes = config.recentChanges(Clock.fixed(Instant.parse("2026-08-17T00:00:00Z"), ZONE));
    assertNotNull(changes);
    assertTrue(changes.isEmpty());
  }

  @Test
  void recentChangesIsSingletonAcrossContext() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.register(RecentChangesConfiguration.class, TestConfiguration.class);
      context.refresh();
      RecentChanges first = context.getBean(RecentChanges.class);
      RecentChanges second = context.getBean(RecentChanges.class);
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