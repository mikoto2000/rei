package dev.mikoto2000.rei.core.stagnation;

import static org.junit.jupiter.api.Assertions.assertSame;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class StagnationSessionTest {

  private static final ZoneId ZONE = ZoneId.of("Asia/Tokyo");

  @Test
  void stagnationDetectorIsSingletonAcrossContext() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.register(StagnationConfiguration.class, TestConfiguration.class);
      context.refresh();
      StagnationDetector first = context.getBean(StagnationDetector.class);
      StagnationDetector second = context.getBean(StagnationDetector.class);
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
