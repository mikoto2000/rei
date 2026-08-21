package dev.mikoto2000.rei.core.taskstate;

import static org.junit.jupiter.api.Assertions.assertSame;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Task State がセッション（アプリケーション全体のシングルトン Bean）として保持されることを検証する。
 *
 * <p>Working Set と同じく、Task State はシングルトン Bean として保持され、
 * Advisor と Tools が同じインスタンスを共有する。</p>
 */
class TaskStateSessionTest {

  private static final ZoneId ZONE = ZoneId.of("Asia/Tokyo");

  @Test
  void taskStateIsSingletonAcrossContext() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.register(TaskStateConfiguration.class, TestConfiguration.class);
      context.refresh();
      TaskState first = context.getBean(TaskState.class);
      TaskState second = context.getBean(TaskState.class);
      assertSame(first, second);
    }
  }

  @Test
  void advisorAndToolsShareSameTaskStateInstance() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.register(TaskStateConfiguration.class, TaskStateAdvisor.class, TaskStateTools.class,
          TestConfiguration.class);
      context.refresh();
      TaskStateAdvisor advisor = context.getBean(TaskStateAdvisor.class);
      TaskStateTools tools = context.getBean(TaskStateTools.class);
      assertSame(advisor.taskState(), tools.taskState());
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
