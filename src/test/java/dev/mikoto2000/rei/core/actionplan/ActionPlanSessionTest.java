package dev.mikoto2000.rei.core.actionplan;

import static org.junit.jupiter.api.Assertions.assertSame;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 操作計画会話の結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class ActionPlanSessionTest {

  /** 検証に使うタイムゾーン。 */
  private static final ZoneId ZONE = ZoneId.of("Asia/Tokyo");

  /** 行動計画の管理で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void actionPlanIsSingletonAcrossContext() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.register(ActionPlanConfiguration.class, TestConfiguration.class);
      context.refresh();
      ActionPlan first = context.getBean(ActionPlan.class);
      ActionPlan second = context.getBean(ActionPlan.class);
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
