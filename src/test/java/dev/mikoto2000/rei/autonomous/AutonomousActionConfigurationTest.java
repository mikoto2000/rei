package dev.mikoto2000.rei.autonomous;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mikoto2000.rei.application.state.OperationConflictException;
import dev.mikoto2000.rei.event.AgentEventBus;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import dev.mikoto2000.rei.storage.StorageDatabase;
import dev.mikoto2000.rei.storage.StorageMigrationConfiguration;
import dev.mikoto2000.rei.storage.StorageMigrationCoordinator;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

@Tag("integration")
class AutonomousActionConfigurationTest {
  @TempDir Path root;

  private ApplicationContextRunner context() {
    return new ApplicationContextRunner()
        .withUserConfiguration(
            StorageMigrationConfiguration.class, AutonomousActionConfiguration.class)
        .withBean(Clock.class, Clock::systemUTC)
        .withBean(AgentEventBus.class, InMemoryAgentEventBus::new)
        .withPropertyValues("rei.data-dir=" + root);
  }

  @Configuration(proxyBeanMethods = false)
  @ComponentScan(basePackageClasses = AutonomousActionConfiguration.class)
  static class ScannedModule {}

  @Test
  void componentScanningAndModuleImportDoNotCreateDuplicateRepositories() {
    context()
        .withUserConfiguration(ScannedModule.class)
        .run(context ->
            assertThat(context)
                .hasNotFailed()
                .hasSingleBean(AutonomousDraftStore.class)
                .hasSingleBean(AutonomousDraftLifecycle.class));
  }

  @Test
  void foundationIsAvailableWithoutAChatModelAndRemainsDisabled() {
    context()
        .run(context -> {
          assertThat(context).hasNotFailed().hasSingleBean(AutonomousDraftStore.class);
          assertThat(context.getBean(AutonomousActionProperties.class).enabled()).isFalse();
          var result =
              context
                  .getBean(AutonomousDraftStore.class)
                  .reserve(
                      "disabled", AutonomousDraft.Trigger.MANUAL, "did:plc:example", "rei.example");
          assertThat(result.decision().reason()).isEqualTo("disabled");
          assertThat(result.claim()).isNull();
        });
  }

  @Test
  void unknownSettingsDoNotSilentlyEnableOrBypassLimits() {
    context()
        .withPropertyValues("rei.autonomous-action.bluesky.unknown=true")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void realComponentStartupAbandonsInterruptedConsiderationWithoutSavingADraft() throws Exception {
    try (var migration = new StorageMigrationCoordinator(root)) {
      migration.prepare();
    }
    var defaults = AutonomousActionProperties.Bluesky.defaults();
    var settings =
        new AutonomousActionProperties(
            true,
            new AutonomousActionProperties.Bluesky(
                true,
                defaults.cron(),
                defaults.zone(),
                1,
                1,
                defaults.cooldown(),
                defaults.draftExpiration(),
                defaults.maxRegenerations(),
                defaults.regenerationCooldown(),
                defaults.systemPrompt()));
    var old = new AutonomousDraftStore(new StorageDatabase(root), Clock.systemUTC(), settings);
    var claim =
        old.reserve(
                "interrupted-startup",
                AutonomousDraft.Trigger.MANUAL,
                "did:plc:example",
                "rei.example")
            .claim();
    context()
        .run(context -> {
          assertThat(context).hasNotFailed().hasSingleBean(AutonomousDraftStore.class);
          var restored = context.getBean(AutonomousDraftStore.class);
          assertThat(restored.list(20)).isEmpty();
          assertThatThrownBy(() -> restored.complete(claim, "自動再開しない本文", "reason", List.of()))
              .isInstanceOf(OperationConflictException.class);
        });
  }
}
