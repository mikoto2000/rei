package dev.mikoto2000.rei.autonomous;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class AutonomousActionPropertiesTest {
  private AutonomousActionProperties bind(Map<String, String> values) {
    return new Binder(new MapConfigurationPropertySource(values))
        .bindOrCreate("rei.autonomous-action", Bindable.of(AutonomousActionProperties.class));
  }

  @Test
  void defaultsDisableBothFeaturesAndBoundGeneration() {
    var settings = bind(Map.of());
    assertThat(settings.enabled()).isFalse();
    assertThat(settings.bluesky().enabled()).isFalse();
    assertThat(settings.bluesky().cron()).isEqualTo("0 0 12 * * *");
    assertThat(settings.bluesky().zone()).isEmpty();
    assertThat(settings.bluesky().maxDraftsPerDay()).isEqualTo(1);
    assertThat(settings.bluesky().maxPendingDrafts()).isEqualTo(1);
    assertThat(settings.bluesky().cooldown()).isEqualTo(Duration.ofHours(1));
    assertThat(settings.bluesky().draftExpiration()).isEqualTo(Duration.ofHours(72));
    assertThat(settings.bluesky().maxRegenerations()).isEqualTo(3);
  }

  @Test
  void frequencyDoesNotChangeDailyLimitAndPromptIsConfigurable() {
    var settings =
        bind(
            Map.of(
                "rei.autonomous-action.bluesky.cron",
                "0 */10 * * * *",
                "rei.autonomous-action.bluesky.system-prompt",
                "custom",
                "rei.autonomous-action.bluesky.zone",
                "Asia/Tokyo"));
    assertThat(settings.bluesky().maxDraftsPerDay()).isEqualTo(1);
    assertThat(settings.bluesky().systemPrompt()).isEqualTo("custom");
    assertThat(settings.bluesky().zone()).isEqualTo("Asia/Tokyo");
  }

  @ParameterizedTest
  @CsvSource({
    "cron,invalid",
    "zone,Mars/Test",
    "max-drafts-per-day,0",
    "max-pending-drafts,0",
    "cooldown,PT0S",
    "draft-expiration,-PT1H",
    "max-regenerations,-1",
    "regeneration-cooldown,PT0S"
  })
  void invalidSettingsFailEvenWhileFeatureIsDisabled(String key, String value) {
    assertThatThrownBy(() -> bind(Map.of("rei.autonomous-action.bluesky." + key, value)))
        .isInstanceOf(org.springframework.boot.context.properties.bind.BindException.class);
  }
}
