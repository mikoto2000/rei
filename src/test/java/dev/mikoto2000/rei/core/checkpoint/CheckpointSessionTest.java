package dev.mikoto2000.rei.core.checkpoint;

import static org.junit.jupiter.api.Assertions.assertSame;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 再開用の記録会話の結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class CheckpointSessionTest {

  /** 検証に使うタイムゾーン。 */
  private static final ZoneId ZONE = ZoneId.of("Asia/Tokyo");

  /** 実行状態の引き継ぎで、指定した条件に対する結果と状態の変化を確認する。 */
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

  /** 起動単位の識別子を保存することを確認する。 */
  @Test
  void advisorSharesSameCheckpointStoreInstance() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.register(
          CheckpointConfiguration.class, CheckpointAdvisor.class, TestConfiguration.class);
      context.refresh();
      CheckpointAdvisor advisor = context.getBean(CheckpointAdvisor.class);
      CheckpointStore store = context.getBean(CheckpointStore.class);
      assertSame(advisor.checkpointStore(), store);
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
