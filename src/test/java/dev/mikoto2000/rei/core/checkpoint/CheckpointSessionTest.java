package dev.mikoto2000.rei.core.checkpoint;

import static org.junit.jupiter.api.Assertions.assertSame;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class CheckpointSessionTest {

  private static final ZoneId ZONE = ZoneId.of("Asia/Tokyo");

  @Test
  void checkpointStoreIsSingletonAcrossContext() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.register(CheckpointConfiguration.class, TestConfiguration.class);
      context.refresh();
      CheckpointStore first = context.getBean(CheckpointStore.class);
      CheckpointStore second = context.getBean(CheckpointStore.class);
      assertSame(first, second);
    }
  }

  @Test
  void advisorSharesSameCheckpointStoreInstance() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.register(CheckpointConfiguration.class, CheckpointAdvisor.class, TestConfiguration.class);
      context.refresh();
      CheckpointAdvisor advisor = context.getBean(CheckpointAdvisor.class);
      CheckpointStore store = context.getBean(CheckpointStore.class);
      assertSame(advisor.checkpointStore(), store);
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
