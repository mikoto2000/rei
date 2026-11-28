package dev.mikoto2000.rei.autonomous;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class AutonomousSchedulingPropertiesTest {
  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(AutonomousActionProperties.class)
  static class SettingsOnly {}

  private AutonomousActionProperties bind(Map<String, String> values) {
    return new Binder(new MapConfigurationPropertySource(values))
        .bindOrCreate("rei.autonomous-action", Bindable.of(AutonomousActionProperties.class));
  }

  @Test
  void defaultSchedulingDoesNotEnableEitherFeature() {
    var settings = bind(Map.of());
    assertThat(settings.enabled()).isFalse();
    assertThat(settings.bluesky().enabled()).isFalse();
    assertThat(settings.bluesky().scheduledEnabled()).isTrue();
  }

  @Test
  void periodicConsiderationCanBeDisabledWithoutDisablingManualFeatureSettings() {
    var settings =
        bind(
            Map.of(
                "rei.autonomous-action.enabled", "true",
                "rei.autonomous-action.bluesky.enabled", "true",
                "rei.autonomous-action.bluesky.scheduled-enabled", "false"));
    assertThat(settings.enabled()).isTrue();
    assertThat(settings.bluesky().enabled()).isTrue();
    assertThat(settings.bluesky().scheduledEnabled()).isFalse();
    assertThat(settings.bluesky().maxDraftsPerDay()).isEqualTo(1);
  }

  @Test
  void enablingTheScheduleAloneDoesNotEnableGeneration() {
    var settings = bind(Map.of("rei.autonomous-action.bluesky.scheduled-enabled", "true"));
    assertThat(settings.bluesky().scheduledEnabled()).isTrue();
    assertThat(settings.enabled()).isFalse();
    assertThat(settings.bluesky().enabled()).isFalse();
  }

  @Test
  void existingProgrammaticSettingsKeepTheirPeriodicDefault() {
    var d = AutonomousActionProperties.Bluesky.defaults();
    var configured =
        new AutonomousActionProperties.Bluesky(
            true,
            d.cron(),
            d.zone(),
            d.maxDraftsPerDay(),
            d.maxPendingDrafts(),
            d.cooldown(),
            d.draftExpiration(),
            d.maxRegenerations(),
            d.regenerationCooldown(),
            d.systemPrompt());
    assertThat(configured.scheduledEnabled()).isTrue();
    assertThat(configured.enabled()).isTrue();
  }

  @Test
  void springUsesTheCanonicalRecordConstructorForAnExplicitManualOnlyConfiguration() {
    new ApplicationContextRunner()
        .withUserConfiguration(SettingsOnly.class)
        .withPropertyValues(
            "rei.autonomous-action.enabled=true",
            "rei.autonomous-action.bluesky.enabled=true",
            "rei.autonomous-action.bluesky.scheduled-enabled=false",
            "rei.autonomous-action.bluesky.cron=0 */10 * * * *")
        .run(context -> {
          assertThat(context).hasNotFailed();
          var settings = context.getBean(AutonomousActionProperties.class);
          assertThat(settings.enabled()).isTrue();
          assertThat(settings.bluesky().enabled()).isTrue();
          assertThat(settings.bluesky().scheduledEnabled()).isFalse();
          assertThat(settings.bluesky().cron()).isEqualTo("0 */10 * * * *");
        });
  }

  @Test
  void invalidBooleanIsRejectedAtStartup() {
    new ApplicationContextRunner()
        .withUserConfiguration(SettingsOnly.class)
        .withPropertyValues("rei.autonomous-action.bluesky.scheduled-enabled=later")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void unknownSettingsStillFailClosed() {
    new ApplicationContextRunner()
        .withUserConfiguration(SettingsOnly.class)
        .withPropertyValues("rei.autonomous-action.bluesky.unexpected=true")
        .run(context -> assertThat(context).hasFailed());
  }
}
