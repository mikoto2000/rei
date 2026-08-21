package dev.mikoto2000.rei.core.relatedgraph;

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
 * Related File Graph がセッション（アプリケーション全体のシングルトン Bean）として保持されることを検証する。
 */
class RelatedFileGraphSessionTest {

  private static final ZoneId ZONE = ZoneId.of("Asia/Tokyo");

  @Test
  void providesRelatedFileGraphBean() {
    RelatedFileGraphConfiguration config = new RelatedFileGraphConfiguration();
    RelatedFileGraph graph = config.relatedFileGraph(Clock.fixed(Instant.parse("2026-08-17T00:00:00Z"), ZONE));
    assertNotNull(graph);
    assertTrue(graph.isEmpty());
  }

  @Test
  void relatedFileGraphIsSingletonAcrossContext() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.register(RelatedFileGraphConfiguration.class, TestConfiguration.class);
      context.refresh();
      RelatedFileGraph first = context.getBean(RelatedFileGraph.class);
      RelatedFileGraph second = context.getBean(RelatedFileGraph.class);
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
