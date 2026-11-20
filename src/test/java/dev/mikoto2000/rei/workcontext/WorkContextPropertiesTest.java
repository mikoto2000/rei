package dev.mikoto2000.rei.workcontext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class WorkContextPropertiesTest {
  @Test
  void bindsNewLimitsWithoutLosingExistingDefaults() {
    var properties =
        new Binder(
                new MapConfigurationPropertySource(
                    Map.of(
                        "rei.work-context.auto-update", "true",
                        "rei.work-context.max-timeout-retries", "1",
                        "rei.work-context.batch-timeout-seconds", "30",
                        "rei.work-context.max-output-tokens", "2048")))
            .bind("rei.work-context", Bindable.of(WorkContextProperties.class))
            .orElseThrow(() -> new IllegalStateException("Missing Work Context properties"));
    assertEquals(1, properties.maxTimeoutRetries());
    assertEquals(30, properties.batchTimeoutSeconds());
    assertEquals(2048, properties.maxOutputTokens());
    assertEquals(240, properties.timeoutSeconds());
    assertEquals("memory", properties.modelFeature());
    assertEquals(
        0, new WorkContextProperties(false, true, 1200, 12000, 240, 20).maxTimeoutRetries());
  }

  @Test
  void rejectsInvalidModelAndRetryBudgets() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new WorkContextProperties(
                false, true, 1200, 12000, 240, 20, 4000, 16, "memory", 3, 60, 4096));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new WorkContextProperties(
                false, true, 1200, 12000, 240, 20, 4000, 16, "memory", 0, 0, 4096));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new WorkContextProperties(
                false, true, 1200, 12000, 240, 20, 4000, 16, "memory", 0, 60, 0));
  }
}
