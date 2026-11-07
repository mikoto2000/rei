package dev.mikoto2000.rei.topic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class TopicGeneratorPropertiesTest {

  @Test
  void bindsNestedSettingsAndKeepsDefaults() {
    var source =
        new MapConfigurationPropertySource(
            Map.of(
                "rei.topic-generator.idle-trigger.enabled", "false",
                "rei.topic-generator.curiosity.expiration", "10d",
                "rei.topic-generator.discovery.max-seeds", "8"));
    var properties =
        new Binder(source)
            .bind("rei.topic-generator", Bindable.of(TopicGeneratorProperties.class))
            .get();

    assertFalse(properties.getIdleTrigger().isEnabled());
    assertEquals(Duration.ofMinutes(2), properties.getIdleTrigger().getMinimumIdle());
    assertEquals(Duration.ofDays(10), properties.getCuriosity().getExpiration());
    assertEquals(8, properties.getDiscovery().getMaxSeeds());
    assertEquals(30, properties.getDiscovery().getFreshnessWindowDays());
  }

  @Test
  void nestedSettingsAreCopiedOnReadAndWrite() {
    var properties = new TopicGeneratorProperties();
    var discovery = properties.getDiscovery();
    discovery.setMaxSeeds(8);
    assertEquals(3, properties.getDiscovery().getMaxSeeds());

    properties.setDiscovery(discovery);
    discovery.setMaxSeeds(9);
    assertEquals(8, properties.getDiscovery().getMaxSeeds());
  }
}
