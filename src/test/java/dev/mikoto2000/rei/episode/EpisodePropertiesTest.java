package dev.mikoto2000.rei.episode;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
class EpisodePropertiesTest {
 @Configuration @EnableConfigurationProperties(EpisodeProperties.class) static class Settings {}
 @Test void bindsBothFlagsThroughTheCanonicalRecordConstructor() {
  new ApplicationContextRunner().withUserConfiguration(Settings.class)
    .withPropertyValues("rei.memory.episodes.enabled=true","rei.memory.episodes.dense-enabled=true")
    .run(context->{assertNull(context.getStartupFailure());var properties=context.getBean(EpisodeProperties.class);assertTrue(properties.enabled());assertTrue(properties.denseEnabled());});
 }
 @Test void denseConfigurationNeverNeedsAProviderWhenDisabledOrEmbeddingIsDisabled() {
  new ApplicationContextRunner().withUserConfiguration(EpisodeDenseConfiguration.class).run(context->{assertNull(context.getStartupFailure());assertFalse(context.containsBean("episodeDenseIndex"));});
  new ApplicationContextRunner().withUserConfiguration(EpisodeDenseConfiguration.class).withPropertyValues("rei.memory.episodes.dense-enabled=true","rei.embedding.enabled=false")
    .run(context->{assertNull(context.getStartupFailure());assertFalse(context.containsBean("episodeDenseIndex"));});
 }
}
