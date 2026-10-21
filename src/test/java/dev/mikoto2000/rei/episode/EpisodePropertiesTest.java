package dev.mikoto2000.rei.episode;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** 経験の記録設定の結果と状態の契約を検証するテスト。 */
class EpisodePropertiesTest {
  /** 検証条件を再現するための設定。 */
  @Configuration
  @EnableConfigurationProperties(EpisodeProperties.class)
  static class Settings {}

  /** 過去の経験の活用で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void bindsBothFlagsThroughTheCanonicalRecordConstructor() {
    new ApplicationContextRunner()
        .withUserConfiguration(Settings.class)
        .withPropertyValues(
            "rei.memory.episodes.enabled=true", "rei.memory.episodes.dense-enabled=true")
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              var properties = context.getBean(EpisodeProperties.class);
              assertTrue(properties.enabled());
              assertTrue(properties.denseEnabled());
            });
  }

  /** 過去の経験の活用で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void denseConfigurationNeverNeedsAProviderWhenDisabledOrEmbeddingIsDisabled() {
    new ApplicationContextRunner()
        .withUserConfiguration(EpisodeDenseConfiguration.class)
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              assertFalse(context.containsBean("episodeDenseIndex"));
            });
    new ApplicationContextRunner()
        .withUserConfiguration(EpisodeDenseConfiguration.class)
        .withPropertyValues("rei.memory.episodes.dense-enabled=true", "rei.embedding.enabled=false")
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              assertFalse(context.containsBean("episodeDenseIndex"));
            });
  }
}
